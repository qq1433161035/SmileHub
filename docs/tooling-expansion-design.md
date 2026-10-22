# SmileHub 工具能力扩展设计（v2：成熟库优先）

> 目标：在现有 AI 对话能力之上，让 Agent 直接操作 APK / DEX / SO / ADB，形成
> "分析 → 修改 → 重打包 → 真机验证"的闭环。
>
> **v2 修订说明**：v1 以"Android 无法运行桌面 JVM 工具"为由绕开 apktool，改为自研
> 精简实现，这是错误取舍。apktool、smali/baksmali、dexlib2、apksig 都是纯 Java 库，
> 可作为依赖在 ART 上运行；本版改为**成熟库作为主引擎**，自研代码只做适配与编排。
> 数据设计（第 6 章）与 v1 保持一致。

---

## 1. 结论先行：采用哪些成熟组件

| 能力 | 成熟组件 | 在 Android 上的运行方式 | 备注 |
| --- | --- | --- | --- |
| APK 反编译 / 重打包 | **apktool-lib**（`brut.apktool:apktool-lib`） | 作为 Gradle 依赖打进 APK，进程内调用 | 资源解码/重建、Manifest、`resources.arsc` 全量支持 |
| APK 构建所需的资源编译 | **aapt2**（AOSP） | **arm64-v8a 可执行文件**放 `jniLibs`/`assets`，运行时释放并授权 | 唯一必须自行解决架构的组件 |
| DEX 反汇编 / 汇编 | **smali / baksmali** | 纯 Java 库 | apktool 2.10 起同步升级到 3.0 |
| DEX 读写 API | **dexlib2** | 纯 Java 库 | 类/方法/指令级读写 |
| APK 签名（v1/v2/v3/v4） | **apksig**（`com.android.tools.build:apksig`） | 纯 Java 库 | Android 上已被大量应用使用 |
| ZIP 对齐 | **zipalign**（AOSP）或 4/16KB 对齐自实现 | arm64 可执行文件 / 纯 Kotlin | 对齐逻辑本身简单，可自实现 |
| Java 反编译 | **jadx-core** | 纯 Java 库（需评估 Android 兼容性） | 备选 CFR，体积更小、质量略低 |
| SO 解析 / 改写 | **LIEF** | NDK 交叉编译 JNI | ELF 头/节/符号/重定位/段编辑 |
| SO 反汇编 | **Capstone** | NDK 交叉编译 JNI | arm/arm64/x86_64 |
| SO 汇编（回填指令） | **Keystone** | NDK JNI | `so.assemble` 默认启用 |
| 加固 / 引擎特征识别 | 自维护特征规则（签名/字符串/SO 名） | 纯 Kotlin | APKiD 是 Python 工具，运行时形态不适合内嵌，规则可借鉴 |
| ADB 主机协议 | **adblib**（`com.tananaev:adblib`） | 纯 Java 库 | 经典 `tcpip 5555` 通道成熟；**无线配对需单独验证/补齐** |
| ADB 备选 | **dadb**（mobile-dev-inc） | Kotlin/JVM 库 | 需验证 Android 运行时与配对支持 |
| 大文件/二进制读取 | Java NIO + mmap | 系统 API | 避免整文件读入内存 |

> 版本策略：不追最新，**先锁定一组在 Android 上验证通过的版本**（apktool 2.10.x /
> smali 3.x / apksig 与 AGP 同版本 / LIEF 0.15+ / Capstone 5.x），并在 CI 中加一台
> 真机的 instrumentation 冒烟测试。

---

## 2. 为什么这些库能跑在 Android 上，以及真正的坑

apktool 等是 JVM 库，不是桌面可执行程序，因此可以直接被 APK 引用。真正的适配点只有
以下几类，全部可以在 `Toolchain` 层收敛：

| 坑 | 具体表现 | 处理方式 |
| --- | --- | --- |
| **aapt2 是平台二进制** | apktool 构建资源时调用 aapt2（2.10 起仅支持 aapt2，可用 `--aapt` 指定外部二进制），官方 jar 内只带 windows/linux/macos 的 x86_64 版本 | 已打包静态 aarch64 `aapt2`/`zipalign` 到 `jniLibs`（`lib*_exec.so` + `useLegacyPackaging`），在真机上执行通过 |
| **JVM 系统属性未设置** | Android 不定义 `os.name` / `sun.arch.data.model`，apktool 的 `OSDetection` 静态初始化直接 NPE | `ApkEngine` 构造时补齐 `os.name=Linux`、`os.arch=aarch64`、`os.version`、`sun.arch.data.model=64`、`user.home` |
| **java.awt / javax.imageio 缺失** | apktool 的 `Res9patchStreamDecoder` 用 AWT 读写 PNG，Android 没有 AWT，且 `java.*` 包不允许应用自定义 | 从 apktool-lib 中移除该类，用同包名 Kotlin 类基于 `Bitmap`/`Canvas` 重写，九宫格 chunk 解析仍复用 apktool 的 `NinePatchData` |
| **ProGuard/R8** | apktool/smali 依赖反射与 ServiceLoader，release 构建会被裁剪 | keep 规则：`brut.**`、`org.jf.**`、`com.android.apksig.**`、`com.android.tools.**`、XML/JSON 解析实现类 |
| **依赖冲突** | Guava、commons-*、`META-INF` 重名、xpp3 传递的 junit 4.7 | 统一 Guava 33.3.0-android；强制 junit 4.13.2；`packaging.resources.excludes` 清理 META-INF |
| **内存** | 大 APK（>1GB）解码时对象膨胀，256MB 堆会 OOM | `android:largeHeap="true"`；工具超时与项目大小限制；后续把工具执行迁到 `:tooling` 进程 |
| **framework 资源** | 厂商定制 framework 需要额外 framework apk | 内置 apktool 自带 framework；支持用户导入 OEM framework，存 `filesDir/framework/` |
| **文件权限** | aapt2 释放到私有目录后无执行权限 | 以 `lib*_exec.so` 随 nativeLibraries 安装，直接从 `nativeLibraryDir` 执行 |
| **SELinux** | 应用私有目录内执行自解压二进制受限 | 通过 `nativeLibraryDir` 规避；已在 HyperOS/Android 16 真机验证 |

---

## 3. 总体架构

```
┌──────────────────────────── UI 层 ────────────────────────────┐
│ AgentScreen（对话）        工程页（项目/文件/操作/发现/ADB）      │
└───────────────────────────────┬───────────────────────────────┘
                                │ Binder / 事件流
┌──────────────── 工具进程 :tooling（独立进程，隔离内存与崩溃） ────────────────┐
│ ToolRegistry → ToolExecutor（审批/超时/截断/审计/产物登记）                    │
│   apk.*   dex.*   so.*   adb.*   project.*   artifact.*   （+ 原 read/write/edit/bash）│
│                                                                             │
│ Engine 适配层（薄）：                                                          │
│   ApktoolEngine ─ apktool-lib + aapt2(arm64) + apksig + zipalign             │
│   DexEngine     ─ baksmali/smali + dexlib2                                   │
│   ElfEngine     ─ LIEF + Capstone（+ Keystone 可选）                          │
│   AdbEngine     ─ adblib（+ 配对实现）                                        │
│   Toolchain     ─ aapt2/zipalign 释放与版本自检、JVM API 兼容 shim             │
└───────────────────────────────┬─────────────────────────────────────────────┘
                                │
┌─────────────────────────── 数据层 ────────────────────────────┐
│ Workspace（文件树） ProjectDb（SQLite） 日志/审计  SecurePrefs  │
└───────────────────────────────────────────────────────────────┘
```

设计原则：

1. **能直接用库就不自研**：apktool 负责资源全量往返，smali 负责 dex 往返，apksig 负责
   签名，LIEF/Capstone 负责 ELF，adblib 负责 ADB。
2. **自研只做三件事**：工具协议与审批、路径/配额/审计、为 Android 补齐缺失 API 与
   arm64 二进制。绝不重写反编译或重打包核心。
3. **独立进程**：`android:process=":tooling"`，避免大内存解析拖垮 UI 与本地转发服务；
   崩溃只影响工具进程，可重启恢复。
4. **产物不可变**：`original/` 只读；每次构建产生新 `artifacts` 行，带 `parent_id` 血缘。

---

## 4. 能力一：APK 读写与重打包（基于 apktool）

### 4.1 引擎接口（对上层稳定，对库是薄封装）

```kotlin
interface ApkEngine {
    fun info(apk: File): ApkMetaData
    fun decode(apk: File, dest: File, options: DecodeOptions): DecodeResult
    fun build(decodedDir: File, out: File, options: BuildOptions): BuildResult
    fun sign(apk: File, key: SigningKey, schemes: Set<Scheme>): SignResult
    fun verify(apk: File): VerifyReport
    fun align(apk: File, out: File, pageSize: Int): AlignResult
}
```

内部实现直接调用：

- `brut.androlib.ApkDecoder` → 解码到 `extract/`（含 `res/`、`smali*/`、`AndroidManifest.xml`、`resources.arsc`）
- `brut.androlib.ApkBuilder` → 从目录重建未签名 APK
- `brut.androlib.Config` → 指定 `aapt2` 路径、framework 目录、是否保留原始 Manifest
- `com.android.apksig.ApkSigner` → v1/v2/v3/v4 签名
- `zipalign` 二进制或内置 4KB/16KB 对齐

### 4.2 端到端流程

```
导入 APK ── artifacts(APK, sha256) ── 写入 original/（只读）
   │
   ├─ apk.info ──────────────► ApkMetaData（包名/权限/签名/ABI/组件）
   ├─ apk.decode ────────────► extract/（apktool 全量解码：资源 + smali）
   ├─ dex.* / so.* / 文本编辑 ► 修改 extract/ 中的 smali、lib、assets、res
   ├─ apk.build ─────────────► build/unsigned.apk（apktool + aapt2 资源编译）
   ├─ apk.align ─────────────► build/aligned.apk（4KB / 16KB page）
   ├─ apk.sign ──────────────► out/{name}-signed.apk（新 artifact）
   └─ apk.verify / adb.install ► 验签 + 真机安装
```

### 4.3 新增工具（APK）

| 工具 | 风险 | 参数 | 说明 |
| --- | --- | --- | --- |
| `apk.info` | READ | `path` | 清单、权限、ABI、签名证书指纹、dex/so 概览 |
| `apk.decode` | WRITE | `path`,`dest?`,`smali=true`,`res=true` | apktool 全量解码（默认 smali + 资源） |
| `apk.build` | WRITE | `dir`,`out?`,`aapt2?` | apktool 重打包；失败返回 aapt2 日志尾部 |
| `apk.align` | WRITE | `apk`,`page_size?` | 4KB（默认）/16KB |
| `apk.sign` | SENSITIVE | `apk`,`keystore_id`,`scheme?` | v1/v2/v3 组合，默认 v2+v3 |
| `apk.verify` | READ | `apk` | 各方案结果、证书链、zip 完整性 |
| `apk.unzip` / `apk.list` / `apk.read` / `apk.replace` / `apk.remove` | READ/WRITE | 同 v1 | 轻量路径，用于快速替换而不触发全量解码 |
| `apk.install` | EXEC | `apk`,`serial?` | 走 `adb.install` |

> `apk.build` 是"成熟功能"的关键：资源改动（`res/`、`values`、`AndroidManifest.xml`）
> 由 apktool + aapt2 真正重编译，而不是 v1 的 zip 级替换。

---

## 5. 能力二：DEX / SO（基于 smali / dexlib2 / LIEF / Capstone）

### 5.1 DEX

| 子能力 | 组件 | 工具 |
| --- | --- | --- |
| 反汇编整包/单类 | **baksmali 3.x** | `dex.disasm`（`--classes`/`--method` 过滤） |
| 汇编回写 | **smali 3.x** | `dex.assemble` / `dex.patch`（改 smali 文本 → 重新生成 dex） |
| 类/方法/字符串索引 | **dexlib2 3.x** | `dex.info` / `dex.classes` / `dex.methods` / `dex.strings` |
| 多 dex 处理 | dexlib2 | 自动发现 `classes*.dex`，写入 `dex_index` |
| 资源 ID / 引用检查 | dexlib2 | `apk.build` 前校验 `R` 引用，减少编译失败 |
| Java 反编译（可选） | **jadx-core** 或 CFR | `dex.decompile`（输出到 `reports/`，大文件落盘不内联） |

修改流程固定为：`decode → 改 smali → assemble → replace dex → build → sign`，
每步产物入库，可回滚。

### 5.2 SO

| 子能力 | 组件 | 工具 |
| --- | --- | --- |
| ELF 头/节/段/符号/依赖 | **LIEF**（JNI） | `so.info` / `so.symbols` / `so.deps` / `so.sections` |
| 字符串与交叉引用 | LIEF + 轻量扫描 | `so.strings` / `so.xref` |
| 反汇编 | **Capstone**（JNI） | `so.disasm`（按 vaddr/offset） |
| Patch | LIEF 段编辑 或 字节写 | `so.patch`（file offset / vaddr 自动换算） |
| 汇编回填 | **Keystone**（JNI） | `so.assemble`（支持 arm/arm64 指令回填） |

游戏样本识别规则（自有规则集，不改动样本逻辑）：

- 引擎：`libil2cpp.so` + `global-metadata.dat`、`libunity.so`、`libUE4.so`、`libcocos2d*.so`
- 加固：常见加固特征 SO/段名/入口特征
- 网络：URL/域名/IP 字符串、`libcurl`/`okhttp` 特征

---

## 6. 能力三：ADB 调试（adblib 为主，配对需补齐）

### 6.1 组件现状（必须如实说明）

- **adblib**：Apache-2.0，提供 ADB 主机协议（连接、shell、push/pull、install、forward），
  经典使用方式是设备先 `adb tcpip 5555`。**Android 11 的"无线调试配对码"流程
  （TLS + pairing）不在其文档覆盖范围内，需要自行实现或验证新版本是否支持。**
- **dadb**：Kotlin 实现，接口更现代，同样需验证配对与 Android 运行时兼容性。

因此 ADB 分两级能力：

| 级别 | 前提 | 能力 | 阶段 |
| --- | --- | --- | --- |
| 基础 | USB 执行过一次 `adb tcpip 5555`，或设备 root 后本机 adbd | shell/logcat/push/pull/install/forward | P0 |
| 完整 | Android 11+ 无线调试，输入 6 位配对码 | `adb.pair` + 免 USB 连接 | P1（自研 pairing 或评估库） |

### 6.2 工具

| 工具 | 风险 | 说明 |
| --- | --- | --- |
| `adb.devices` | READ | 已配对/已连接设备 |
| `adb.connect` / `adb.disconnect` | EXEC | host:port |
| `adb.pair` | SENSITIVE | 配对码 + 地址（P1） |
| `adb.shell` | EXEC | 超时、输出截断 |
| `adb.logcat` | READ | 按时长/tag/level 抓取，落盘 |
| `adb.push` / `adb.pull` | WRITE | 文件同步 |
| `adb.install` / `adb.uninstall` | EXEC | 安装失败原因解析（`INSTALL_FAILED_*`） |
| `adb.forward` | EXEC | 调试端口转发 |

---

## 7. 数据设计

### 7.1 存储布局

```
/sdcard/Android/data/com.wbhub.app/files/WBHub/workspace/projects/{projectId}/
├─ project.json                # 可导出的项目快照
├─ original/base.apk           # 只读原件
├─ extract/                    # apktool 解码目录（可重建）
│  ├─ AndroidManifest.xml
│  ├─ res/  smali/  smali_classes2/  assets/
│  └─ lib/{abi}/*.so
├─ build/                      # 未签名/对齐中间产物
├─ out/                        # 已签名交付物
├─ reports/                    # 分析报告与反编译输出
└─ logs/{operationId}.log      # 单次操作完整输出
```

> 工作区放在应用专属外部目录而非根目录 `/sdcard/WBHub`：解码会产生数万个小文件，
> 不应依赖“所有文件访问”授权；导入仍可读取任意路径。

```
filesDir/keys/{keystoreId}.p12    # 签名密钥（口令经 Android Keystore 加密）
filesDir/tooling.db               # SQLite
nativeLibraryDir/libaapt2_exec.so # 随 APK 安装的 aapt2
nativeLibraryDir/libzipalign_exec.so
```

### 7.2 SQLite（与 v1 相同，新增 1 张构建日志关联表）

```sql
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE projects (
    id             TEXT PRIMARY KEY,
    name           TEXT NOT NULL,
    package_name   TEXT,
    version_name   TEXT,
    version_code   INTEGER,
    status         TEXT NOT NULL DEFAULT 'NEW',   -- NEW|DECODED|MODIFIED|BUILT|SIGNED|FAILED
    source_artifact TEXT,
    workspace      TEXT NOT NULL,
    agent_session  TEXT,
    meta_json      TEXT NOT NULL DEFAULT '{}',
    created_at     INTEGER NOT NULL,
    updated_at     INTEGER NOT NULL
);
CREATE INDEX idx_projects_updated ON projects(updated_at DESC);
CREATE INDEX idx_projects_pkg ON projects(package_name);

CREATE TABLE artifacts (
    id          TEXT PRIMARY KEY,
    project_id  TEXT REFERENCES projects(id) ON DELETE CASCADE,
    kind        TEXT NOT NULL,                    -- APK|DEX|SO|SMALI|REPORT|LOG|KEYSTORE
    path        TEXT NOT NULL,
    size        INTEGER NOT NULL,
    sha256      TEXT NOT NULL,
    parent_id   TEXT REFERENCES artifacts(id),
    origin      TEXT NOT NULL,                    -- IMPORT|DECODE|BUILD|PATCH|SIGN|ANALYZE
    arch        TEXT,
    meta_json   TEXT NOT NULL DEFAULT '{}',
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_artifacts_project ON artifacts(project_id, kind);
CREATE INDEX idx_artifacts_sha ON artifacts(sha256);
CREATE INDEX idx_artifacts_parent ON artifacts(parent_id);

CREATE TABLE operations (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  TEXT REFERENCES projects(id) ON DELETE SET NULL,
    session_id  TEXT,
    tool        TEXT NOT NULL,
    args_json   TEXT NOT NULL,
    risk        TEXT NOT NULL,                    -- READ|WRITE|EXEC|SENSITIVE
    approval    TEXT NOT NULL,
    status      TEXT NOT NULL,                    -- OK|FAILED|DENIED|TIMEOUT
    exit_code   INTEGER,
    summary     TEXT NOT NULL DEFAULT '',
    output_ref  TEXT,
    started_at  INTEGER NOT NULL,
    ended_at    INTEGER
);
CREATE INDEX idx_operations_project ON operations(project_id, started_at DESC);
CREATE INDEX idx_operations_tool ON operations(tool, started_at DESC);

CREATE TABLE findings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    artifact_id TEXT REFERENCES artifacts(id),
    category    TEXT NOT NULL,                    -- PERMISSION|ENDPOINT|SECRET|NATIVE|PACKER|ANTI_DEBUG|SIGNATURE|STRING
    severity    TEXT NOT NULL,
    title       TEXT NOT NULL,
    detail      TEXT NOT NULL DEFAULT '',
    location    TEXT,
    evidence    TEXT NOT NULL DEFAULT '{}',
    created_at  INTEGER NOT NULL
);
CREATE INDEX idx_findings_project ON findings(project_id, category, severity);

CREATE TABLE adb_devices (
    serial       TEXT PRIMARY KEY,
    model        TEXT,
    api_level    INTEGER,
    address      TEXT,
    transport    TEXT NOT NULL,                   -- TCP|USB|LOCAL
    paired       INTEGER NOT NULL DEFAULT 0,
    last_seen_at INTEGER NOT NULL
);

CREATE TABLE adb_events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    serial      TEXT NOT NULL,
    project_id  TEXT REFERENCES projects(id) ON DELETE SET NULL,
    action      TEXT NOT NULL,
    payload     TEXT NOT NULL DEFAULT '',
    exit_code   INTEGER,
    output_ref  TEXT,
    started_at  INTEGER NOT NULL,
    ended_at    INTEGER
);
CREATE INDEX idx_adb_events_serial ON adb_events(serial, started_at DESC);

CREATE TABLE recipes (
    id           TEXT PRIMARY KEY,
    project_id   TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name         TEXT NOT NULL,
    steps_json   TEXT NOT NULL,
    result_id    TEXT REFERENCES artifacts(id),
    created_at   INTEGER NOT NULL
);

CREATE TABLE dex_index (
    project_id  TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    dex_name    TEXT NOT NULL,
    class_name  TEXT NOT NULL,
    method_cnt  INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (project_id, dex_name, class_name)
);

-- 构建诊断：aapt2/smali/apktool 的报错原文，便于回填模型与重试
CREATE TABLE build_diagnostics (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    operation_id INTEGER NOT NULL REFERENCES operations(id) ON DELETE CASCADE,
    stage        TEXT NOT NULL,                   -- DECODE|AAPT2|SMALI|PACKAGE|SIGN|ALIGN
    severity     TEXT NOT NULL,                   -- ERROR|WARNING|INFO
    file         TEXT,
    line         INTEGER,
    message      TEXT NOT NULL
);
CREATE INDEX idx_build_diag_op ON build_diagnostics(operation_id, stage);
```

### 7.3 关键 JSON 契约

**ToolSpec**

```json
{
  "name": "apk.build",
  "description": "用 apktool 从解码目录重打包 APK。资源由 aapt2 真实编译。",
  "risk": "WRITE",
  "timeout_seconds": 300,
  "max_output_bytes": 30000,
  "parameters": {
    "type": "object",
    "properties": {
      "dir":  { "type": "string", "description": "apk.decode 输出的目录" },
      "out":  { "type": "string", "description": "输出路径，默认 build/unsigned.apk" },
      "aapt2": { "type": "string", "description": "可选：覆盖内置 aapt2 路径" }
    },
    "required": ["dir"]
  }
}
```

**ToolResult**

```json
{
  "ok": true,
  "summary": "构建成功 → build/unsigned.apk（耗时 42s）",
  "text": "apktool 2.10.x / aapt2 arm64\nwarnings: 3（见 build_diagnostics）",
  "artifacts": [
    { "id": "a_9f2c", "kind": "APK", "path": "projects/p_31/build/unsigned.apk", "sha256": "…" }
  ],
  "diagnostics": [
    { "stage": "AAPT2", "severity": "WARNING", "file": "res/values/strings.xml", "line": 12,
      "message": "duplicate string resource" }
  ],
  "truncated": false
}
```

**ApkMetaData**

```json
{
  "package": "com.example.game",
  "versionName": "1.2.3",
  "versionCode": 123,
  "minSdk": 23,
  "targetSdk": 34,
  "abis": ["arm64-v8a"],
  "permissions": ["android.permission.INTERNET"],
  "signature": { "schemes": ["v2", "v3"], "certSha256": "…" },
  "entryCount": 1842,
  "dexCount": 3,
  "nativeLibs": ["lib/arm64-v8a/libil2cpp.so"],
  "toolchain": { "apktool": "2.10.x", "aapt2": "builtin-arm64", "smali": "3.x" }
}
```

其余（Finding、Recipe、APK 元数据）沿用 v1 定义。

### 7.4 生命周期与配额

| 数据 | 策略 |
| --- | --- |
| `original/`、`out/` | 不自动删除 |
| `extract/`、`build/` | 可重建；"清理中间产物"时删除 |
| `cache/` | LRU，上限 2 GB |
| `logs/`、`operations` | 保留最近 200 条或 30 天 |
| `build_diagnostics` | 随 operation 级联删除 |
| 解包上限 | 单项目 4 GB / 20 万条目；解压比 > 100:1 拒绝 |
| 工具输出 | > 30 KB 落盘，只回填摘要与引用 |

---

## 8. 工具协议与审批

### 8.1 风险分级

| 级别 | 工具 | AUTO 模式 |
| --- | --- | --- |
| READ | `apk.info` `apk.list` `apk.read` `apk.verify` `dex.info` `dex.strings` `dex.classes` `dex.methods` `dex.disasm` `so.info` `so.symbols` `so.strings` `so.deps` `adb.devices` `adb.logcat` | 放行 |
| WRITE | `apk.decode` `apk.build` `apk.align` `apk.unzip` `apk.replace` `apk.remove` `dex.assemble` `dex.patch` `so.patch` `adb.push` `adb.pull` | 需确认 |
| EXEC | `adb.connect` `adb.shell` `adb.install` `adb.uninstall` `adb.forward` | 需确认 |
| SENSITIVE | `apk.sign` `adb.pair` | 需确认 + 二次提示 |

`ToolPolicy` 从"名字是 read 才放行"改为查 `ToolSpec.risk`；`AgentClient` 循环不变，
仅把 `Outcome` 扩展为 `ToolResult`（含 artifacts/diagnostics）。

### 8.2 执行器

```
ToolExecutor.execute(spec, args)
  ├─ 参数校验 + 路径解析（workspace 内，拒绝 ../ 越界）
  ├─ 并发控制（同一项目串行，不同项目并行）
  ├─ 审批（ToolPolicy + ask）
  ├─ 运行引擎（IO 线程池 / :tooling 进程）
  ├─ 输出截断、日志落盘、artifacts/diagnostics 入库
  └─ operations 记录（含耗时、退出码）
```

### 8.3 错误码

| 码 | 场景 |
| --- | --- |
| `E_TOOLCHAIN` | aapt2 无法执行 / 架构不匹配 / 缺 framework |
| `E_DECODE` | apktool 解码失败（加密资源、未知压缩） |
| `E_BUILD` | aapt2/smali 编译错误（附 `build_diagnostics`） |
| `E_VERIFY` | 签名或 zip 校验失败 |
| `E_DEVICE` | 无设备 / 安装失败（附 `INSTALL_FAILED_*`） |
| `E_UNSAFE` | 越界路径、Zip Bomb、超配额 |
| `E_TIMEOUT` | 工具超时（apk.build 默认 300s） |

---

## 9. UI

- **对话页**：Agent 调用 `apk.*` / `dex.*` / `so.*` / `adb.*`，工具卡片显示耗时与产物；
  审批弹窗展示风险级与关键参数（`apk.sign → out/xxx.apk（v2+v3）`）。
- **工程页**（对话页工具栏进入，不新增底部 tab）：
  - 项目列表：包名、版本、状态、体积；
  - 项目详情：文件树（original/extract/build/out）、产物血缘、诊断列表；
  - 操作时间线：单次命令、日志、可"重跑为 recipe"；
  - ADB：设备、连接、快捷 shell、安装。
- 进度：解码/构建为长任务，显示阶段（DEX → 资源 → 打包 → 对齐 → 签名）与实时日志尾部。

状态模型：

```kotlin
data class ToolingState(
    val projects: List<ProjectSummary> = emptyList(),
    val current: ProjectDetail? = null,
    val devices: List<AdbDevice> = emptyList(),
    val running: ToolRun? = null,          // 工具名、阶段、已耗时
    val lastOperation: OperationSummary? = null,
    val error: String = "",
)
```

---

## 10. 代码结构

```
com/wbhub/app/tools/
├─ ToolSpec.kt / ToolRegistry.kt / ToolExecutor.kt / ToolResult.kt
├─ ApkTools.kt / DexTools.kt / ElfTools.kt / AdbTools.kt / ProjectTools.kt
└─ engines/
   ├─ Toolchain.kt        # aapt2/zipalign 释放、版本自检、缺失 JVM API 的兼容层
   ├─ ApktoolEngine.kt    # apktool-lib + apksig + zipalign
   ├─ DexEngine.kt        # baksmali / smali / dexlib2
   ├─ ElfEngine.kt        # LIEF + Capstone（JNI）
   ├─ AdbEngine.kt        # adblib（+ pairing）
   └─ Rules.kt            # 固化/引擎/端点特征规则

com/wbhub/app/tooling/
├─ ToolingService.kt      # :tooling 进程入口，Binder 暴露引擎
├─ ToolingDb.kt / ProjectStore.kt / OperationLog.kt / Workspace.kt
└─ SecureKeys.kt

app/src/main/jniLibs/arm64-v8a/
├─ libaapt2_exec.so       # 以 .so 形式打包的 aapt2 可执行文件（或放 assets 后释放）
├─ libzipalign_exec.so
├─ liblief_jni.so
└─ libcapstone_jni.so
```

对现有代码的改动：

1. `AgentTools` 保留 4 个工具的实现，注册进 `ToolRegistry`；`AgentClient` 仅适配返回类型。
2. `ToolPolicy` 改为按 `ToolSpec.risk` 判定。
3. `AndroidManifest.xml` 增加 `:tooling` 进程声明。
4. `build.gradle.kts` 增加 apktool-lib、smali、apksig 依赖与 R8 keep 规则。

---

## 11. 分阶段实施

| 阶段 | 范围 | 验收标准 |
| --- | --- | --- |
| **P0 工具链打通** | 依赖接入（apktool-lib/smali/apksig）+ arm64 aapt2 与释放执行 + `:tooling` 进程 + `apk.info/decode/build/align/sign/verify` | 在真机上：解码一个真实 APK → 改一行 `strings.xml` 或 smali → 重打包 → 签名 → 安装启动成功 |
| **P1 DEX/SO 分析** | `dex.info/strings/classes/methods/disasm`、`dex.assemble/patch`、`so.info/symbols/strings/deps/disasm/assemble/patch`、LIEF/Capstone/Keystone JNI、findings 规则与报告 | 对样本输出结构化报告；Agent 能基于 `findings` 回答并完成一次 dex 字符串修改闭环 |
| **P2 真机与 ADB** | adblib 接入（shell/logcat/install/push/pull）、`adb.pair` 验证、recipes 重放 | 无线/局域网连接真机，安装产物并抓取启动日志 |
| **P3 增强** | jadx 反编译、资源 diff、Native 交叉引用、项目导入导出 `.wbhubproj` | 按使用反馈排期 |

---

## 12. 风险清单

| 风险 | 概率/影响 | 缓解 |
| --- | --- | --- |
| apktool 在 Android 缺 `java.awt`/JVM 属性 | 已发生 / 阻断解码 | 已完成：系统属性 bootstrap + 9-patch 解码器同包替换，真机全链路通过 |
| apktool/smali 大 APK 内存峰值 | 高 / 崩溃 | `largeHeap`、独立进程、限制单项目大小、失败可重试 |
| R8 裁剪导致运行期找不到类 | 中 / 崩溃 | 完整 keep 规则 + release 通道真机冒烟 |
| adblib 不支持无线配对 | 高 / 功能缺口 | P2 自研 pairing（TLS + 配对协议）或采用其他实现；P0 先支持 tcpip/root |
| 上游模型误操作 | 中 / 数据损坏 | 审批 + 不可变产物 + recipe 回滚 |

---

## 13. 合规与安全边界

- 仅面向自有应用或明确授权样本的兼容性分析、安全研究与学习。
- 不实现绕过授权校验/付费墙、破解 DRM、篡改他人分发物、绕过反作弊。
- 产物仅存本机；工具层不提供上传通道（除既有模型请求）。
- 导出报告前提示敏感信息；签名私钥经 Android Keystore 加密。
