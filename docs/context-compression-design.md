# 上下文压缩设计（Context Compaction）

## 1. 背景

当前 Agent 的每一轮请求都会把完整会话历史原样发给上游：

- `AgentClient.run()` 把 `history + prompt` 放进 `transcript`，此后每轮追加 assistant 消息和工具结果（`AgentClient.kt:80-117`）。
- `buildBody()` 把 `transcript` 逐条序列化，没有裁剪（`AgentClient.kt:287-309`）。
- `AgentStore.save()` 全量落盘（`AgentStore.kt:88-110`）。

结果：长任务（几十次工具调用）会让请求体持续膨胀，最终上游返回 context 超限，或模型开始丢细节、重复劳动。`context_window` 只是透传给上游（`AgentClient.kt:308`），`maxRounds` 只停止循环（`AgentClient.kt:120`），都不解决历史膨胀。

## 2. 目标与非目标

### 目标

1. 单次任务可以无限轮次运行，请求体稳定在一个预算内。
2. 压缩后仍保留：任务目标、当前进度、关键产物路径、未完成事项。
3. 原始信息不丢：被移出上下文的内容落盘，模型可按需读回。
4. 压缩过程可解释、可回滚、可手动触发。
5. 尽量少破坏上游 KV cache：只在阈值处一次性压缩，不做每轮改写。

### 非目标

- 不做向量库 / embedding 检索。
- 不做跨会话长期记忆（另立方案）。
- 不改变上游协议与工具定义。

## 3. 设计原则（对齐主流做法）

| 原则 | 依据 |
| --- | --- |
| 近期原文 + 中期摘要 + 长期外置 | AgentScope 的 compression / truncation / offload 三件套 |
| 工具输出双版本：完整落盘、紧凑版进上下文 | Manus 的 Reduce / Offload / Isolate |
| 摘要结构化，而非自由文本 | AgentScope Java 保留目标 / 状态 / 关键发现 / 下一步 |
| 压缩由阈值触发，压缩动作显式可见 | Kimi Code 的 `/compact` |
| 原文只移出、不删除，可回溯 | 各类 context offload 实践 |

## 4. 总体架构

```
              ┌──────────────────────────────┐
              │        AgentClient.run        │
              │  while (round < maxRounds)    │
              └──────────────┬───────────────┘
                             │ 每轮调用模型前
                             ▼
                 ┌───────────────────────┐
                 │  ContextBudget.plan() │  估算 token、决定是否压缩
                 └──────────┬────────────┘
                   不足阈值  │  超过阈值
                ┌───────────┴────────────┐
                ▼                        ▼
         原样 streamTurn       ContextCompactor.compact()
                                         │
                      ┌──────────────────┼───────────────────┐
                      ▼                  ▼                   ▼
              工具输出瘦身         摘要旧轮次          原文归档到磁盘
              (P0)                 (P1)                (P0/P1 共用)
```

三层职责：

1. **工具输出治理（P0）**：任何超过阈值的工具结果在进入 `transcript` 前落盘，上下文只留摘要 + 路径。
2. **阈值压缩（P1）**：估算 token 超过窗口比例时，把最老的一批完整轮次交给模型生成结构化摘要，替换为一条摘要消息。
3. **手动压缩（P2）**：用户或模型显式触发一次压缩，可指定保留重点。

## 5. 数据结构

### 5.1 会话文件（`AgentStore`）

在现有 JSON 上追加字段，保持向后兼容（缺省即旧行为）：

```json
{
  "id": "s-1729...",
  "title": "逆向某游戏",
  "updatedAt": 1792...,
  "entries": [ ... ],
  "messages": [ ... ],
  "compaction": {
    "summary": "【历史摘要】\n目标：...\n已完成：...\n关键产物：...\n下一步：...",
    "compactedMessages": 42,
    "compactedAt": 1792...,
    "originalTokens": 91000,
    "summaryTokens": 1400,
    "archives": ["agent/sessions/s-1729.../archive/0001.json"]
  }
}
```

`Session` 增加：

```kotlin
data class CompactionState(
    val summary: String,
    val compactedMessages: Int,
    val compactedAt: Long,
    val archives: List<String>,
)
```

### 5.2 归档目录

```
/WBHub/agent/sessions/<sessionId>/
├── <sessionId>.json              # 会话（现有）
├── archive/                      # 被压缩掉的原始消息（P1）
│   └── 0001.json
└── artifacts/                    # 工具输出完整版（P0）
    ├── 0007-bash.txt
    └── 0012-dex_list.txt
```

新增 `AppStorage.DIR_ARTIFACTS = "agent/artifacts"` 或直接挂在会话目录下（推荐后者，删除会话时一并清理）。

### 5.3 工具结果占位符

工具消息的 `content` 改为：

```
[tool output offloaded]
summary: ok: exit code: 0
artifact: /WBHub/agent/sessions/s-1729.../artifacts/0007-bash.txt (12.4 KB)
head:
  exit code: 0
  BUILD SUCCESSFUL in 1m 9s
hint: 需要完整内容时用 read(path=...) 或 bash(grep/tail)
```

约束：

- `role`、`tool_call_id` 不变，保证与 `assistant.tool_calls` 成对；
- 只在写 `transcript` 时替换，UI 展示仍用完整 `ToolResult.render()`；
- 首行摘要取 `ToolResult.summary` 或输出第一行。

## 6. Token 估算

不引入 tokenizer，使用可校准的字符估算：

```kotlin
fun estimateTokens(text: String): Int {
    var ascii = 0
    var other = 0
    for (ch in text) if (ch.code < 128) ascii++ else other++
    return (ascii / 4.0 + other / 1.6).toInt() + 4 // 每条消息固定开销
}
```

- 中文约 1.6 字符/token，英文约 4 字符/token，偏保守；
- 用已有 `Event.Usage.promptTokens` 按会话校准：`ratio = actual / estimated`，滚动平均后修正（P2）；
- 工具结果按 `content` 估算，工具定义按 `tools.definitions` 序列化长度一次性估算。

## 7. 阈值与保留策略

```kotlin
data class BudgetPolicy(
    val effectiveWindow: Int,      // contextWindow>0 ? contextWindow : 128_000
    val triggerRatio: Double = 0.70,
    val targetRatio: Double = 0.45,
    val keepRecentTurns: Int = 6,  // 至少保留的完整用户轮次
    val keepRecentToolResults: Int = 3,
    val minMessagesToCompact: Int = 8,
)
```

- 触发：`estimatedTotal > effectiveWindow * triggerRatio`
- 目标：压缩后 `estimatedTotal <= effectiveWindow * targetRatio`
- 下限：最近 `keepRecentTurns` 个用户轮次、最近 `keepRecentToolResults` 条工具结果永远保留原文；
- 节流：同一会话两次压缩至少间隔 2 轮，避免抖动；
- 当前正在进行的轮次（最后一条 user 之后的内容）永不压缩。

## 8. 上下文装配算法

```kotlin
fun plan(transcript: List<Message>, policy: BudgetPolicy, state: CompactionState): Plan {
    val total = estimateTotal(transcript)
    if (total <= policy.effectiveWindow * policy.triggerRatio) return Plan.None

    val groups = groupIntoTurns(transcript)          // 以 user 消息为界
    val mode = selectOffloadMode(groups, policy)     // 先瘦身工具输出，再摘要

    // 1) 先把"可瘦身"的旧工具结果换成占位符
    val slimmed = offloadOldToolResults(groups, policy)

    // 2) 仍然超预算，才把最老的轮次摘要掉
    val toCompact = oldestTurnsUntil(slimmed, policy.targetRatio)
    if (toCompact.size < policy.minMessagesToCompact) return Plan.None

    return Plan.Compact(toCompact, groups - toCompact)
}
```

分组规则（协议约束）：

1. `assistant(tool_calls)` 与随后的 `tool` 消息必须同组，不能拆开；
2. 摘要边界只能落在 `user` 消息之前；
3. 组内最后一条 `assistant` 无 tool_calls 视为该轮结束。

## 9. 压缩流程

```kotlin
suspend fun compact(state, toCompact, keep, policy): CompactionState {
    archiveToDisk(toCompact)                       // 原文 json，失败则中止压缩
    val prompt = buildSummaryPrompt(state.summary, toCompact, keep)
    val summary = callModel(prompt, maxTokens = 800) // 复用同一凭证/模型
    val merged = if (state.summary.isBlank()) summary
                 else mergeSummary(state.summary, summary)
    return state.copy(summary = merged, compactedMessages = ..., archives = ...)
}
```

关键决策：

- **摘要消息角色**：使用 `user` 角色并带 `[历史摘要]` 前缀，插在保留段之前；不假设网关支持中途 `system`。若后续确认网关支持，可切换为 `system`。
- **增量摘要**：第二次压缩把"已有摘要 + 新被压缩的消息"一起交给模型，避免多轮压缩后细节逐层丢失。
- **压缩输入预算**：被压缩内容超过 32k token 时分批摘要，先分批再合并。
- **不压缩工具定义**：`tools` 每次全量发送，固定成本单独计入预算。

### 摘要模板

```
你是上下文压缩器。只输出以下结构，不要解释：

【历史摘要】
目标：
- ...
已完成：
- ...
关键产物（路径必须原样保留）：
- /WBHub/workspace/...
关键事实 / 约束：
- ...
未完成 / 下一步：
- ...
```

要求：

- 保留所有文件路径、包名、项目 id、签名 key id；
- 保留失败原因与已排除的方案；
- 不写寒暄、不复述工具原始输出；
- 输出上限 800 token，超长截断并记录警告。

## 10. 失败与降级

| 场景 | 处理 |
| --- | --- |
| 归档写盘失败 | 放弃本次压缩，继续用原上下文，记录一条警告 |
| 摘要请求失败 / 超时 | 降级为硬截断：只保留最近 `keepRecentTurns` 轮 + 一条"更早内容已放弃"提示 |
| 摘要结果为空或超长 | 截断到 800 token；为空则使用硬截断 |
| 压缩后仍超预算 | 允许连续压缩一次；仍超则硬截断 |
| 压缩过程中用户发新消息 | 压缩在 `run()` 的工作线程内同步完成，新消息排队；不并发改写 |
| 会话重新打开 | 读取 `compaction` 字段，重建 `transcript` 时用摘要替换对应区间 |

## 11. UI 与交互

1. 压缩发生时插入一条可折叠条目（复用 `CollapsibleRow`）：

   ```
   工具  上下文已压缩：42 条消息 → 摘要 1.4k tokens
        原文：/WBHub/agent/sessions/<id>/archive/0001.json
   ```

2. 展开只显示摘要全文，长按复制。
3. 设置页新增：
   - 自动压缩开关（默认开）
   - 触发比例（50%–90%，默认 70%）
   - 保留最近轮次（2–20，默认 6）
   - 工具输出落盘阈值（4KB–256KB，默认 32KB）
   - 「立即压缩当前会话」按钮
4. 会话列表显示"已压缩"标记，避免用户误以为历史丢失。

## 12. 设置与存储键

`AgentStore` 新增（沿用 `AppStorage.prefs`）：

```kotlin
KEY_COMPACT_ENABLED   = "compact_enabled"     // Boolean，默认 true
KEY_COMPACT_TRIGGER   = "compact_trigger"     // Int 百分比，默认 70
KEY_COMPACT_KEEP      = "compact_keep_turns"  // Int，默认 6
KEY_OFFLOAD_BYTES     = "offload_bytes"       // Int，默认 32768
```

`contextWindow` 继续透传上游，同时作为压缩预算来源；`0` 时按 128k 估算并在 UI 标注"按默认窗口估算"。

## 13. 代码改动清单

| 文件 | 改动 |
| --- | --- |
| `agent/ContextBudget.kt`（新增） | token 估算、分组、`plan()` 纯函数 |
| `agent/ContextCompactor.kt`（新增） | 归档、摘要请求、合并摘要、降级策略 |
| `agent/ArtifactStore.kt`（新增） | 工具输出落盘、占位符生成、保留期清理 |
| `agent/AgentClient.kt` | `run()` 每轮前调用 budget/compactor；`Message` 增加 `compacted` 标记；Usage 回调用于校准 |
| `agent/AgentStore.kt` | 会话 JSON 增加 `compaction`；读写兼容；新增设置键 |
| `agent/AgentTools.kt` | `bash` 输出落盘；`read` 支持读取 artifact（已有能力即可） |
| `tools/ToolExecutor.kt` | 统一在 `execute()` 返回前做输出瘦身，覆盖所有工具 |
| `ui/AgentScreen.kt` | 压缩条目渲染、会话列表"已压缩"标记 |
| `ui/SettingsScreen.kt` | 压缩设置分组与"立即压缩"入口 |
| `MainActivity.kt` | 压缩事件接入 `applyAgentEvent`，写入 `AgentEntry` 并落盘 |
| `storage/AppStorage.kt` | 会话目录下新增 `archive/`、`artifacts/` 常量与清理逻辑 |

## 14. 里程碑

### P0 工具输出治理

- `ArtifactStore` + `ToolExecutor` 统一瘦身；
- `bash`、`dex_list`、`dex_decompile`、`so_info`、`so_disassemble` 输出落盘；
- 上下文中只保留首行 + 路径 + 大小；
- 预期：单轮工具输出从 30k–120k 字符降到 1k 以内。

### P1 阈值压缩

- `ContextBudget` + `ContextCompactor`；
- 归档、结构化摘要、增量合并、硬截断降级；
- 设置项与压缩条目 UI；
- 预期：50 轮任务请求体稳定在窗口 45%–70%。

### P2 可观测与手动控制

- `Event.Usage` 校准估算比例；
- `/compact` 等价入口：工具 `context_compact` 或界面按钮；
- 归档留存策略（按天数 / 总大小清理）；
- 指标看板：每轮 prompt tokens、压缩次数、压缩后任务成功率。

## 15. 测试计划

### 单元测试（JVM）

- `estimateTokens`：中英文混合、空串、超长文本；
- `groupIntoTurns`：assistant+tool 不拆分、并行 tool_calls 同组；
- `plan()`：未超阈值不动作、超阈值选择正确区间、保留最近轮次、节流生效；
- 摘要合并：二次压缩包含已有摘要；
- 归档失败、摘要失败的降级路径。

### 仪器测试（真机）

- 构造 60 轮假 transcript + 假摘要器，验证：
  - 压缩后消息数、token 估算落在目标区间；
  - 每条 `tool` 消息仍有对应 `assistant.tool_calls`；
  - `artifacts/` 与 `archive/` 文件生成且可读；
  - 会话重启后从 `compaction` 正确重建。
- 真实任务：跑一个 30+ 轮逆向流程，记录压缩前后 `prompt_tokens` 与任务完成度。

### 回归

- 现有 `ToolingSmokeTest`、`ReverseToolsTest` 全绿；
- 旧会话（无 `compaction` 字段）可正常打开、继续对话。

## 16. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 摘要丢关键路径 | 模板强制"关键产物原样保留"；原文归档可回查 |
| 网关对消息顺序敏感 | 只替换内容不改角色；摘要用 `user` 角色并放在保留段之前 |
| 压缩本身消耗额度 | 阈值触发 + 节流 + 摘要上限 800 token；压缩调用计入调用记录 |
| 工具输出落盘后模型不再读 | 占位符中给出明确 `hint` 与路径；保留最近 3 条工具结果原文 |
| 磁盘膨胀 | 归档按会话删除；artifacts 按大小/天数清理，默认保留 7 天或 200MB |

## 17. 验收标准

1. 模拟 60 轮任务，请求体 token 估算不超过窗口的 70%。
2. 压缩后仍能正确回答"当前项目 id / 产物路径 / 下一步是什么"。
3. 关闭自动压缩时行为与现在完全一致。
4. 压缩事件在 UI 可见，归档文件可被 `read` 读取。
5. 旧会话无需迁移即可继续使用。
