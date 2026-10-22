package com.wbhub.app.agent

/**
 * 一次模型调用前的上下文治理：先瘦身旧的工具输出，再按预算压缩最老的轮次。
 *
 * 只在 [AgentClient] 的轮次边界调用，因此不会拆开
 * `assistant(tool_calls)` 与后续 `tool` 消息的配对。
 */
class ContextEngine(
    private val artifacts: ArtifactStore,
    private val config: CompressionConfig,
) {

    data class CompressionConfig(
        val enabled: Boolean = true,
        val triggerRatio: Double = 0.70,
        val targetRatio: Double = 0.45,
        val keepRecentTurns: Int = 6,
        val keepRecentToolResults: Int = 3,
        /** 0 表示按默认窗口估算。 */
        val contextWindow: Int = 0,
        val offloadChars: Int = 32_000,
    )

    data class CompactionEvent(
        val removed: Int,
        val summaryTokens: Int,
        val archivePath: String?,
        val fallback: Boolean,
    )

    data class Step(
        val messages: List<AgentClient.Message>,
        val state: CompactionState,
        val offloaded: Int,
        val compaction: CompactionEvent?,
    )

    fun prepare(
        messages: List<AgentClient.Message>,
        sessionId: String,
        toolsTokens: Int,
        state: CompactionState,
        /** 手动触发时忽略阈值，至少压缩一组较早的轮次。 */
        force: Boolean = false,
        summarize: (String) -> String?,
    ): Step {
        if (!config.enabled) return Step(messages, state, 0, null)

        val policy = ContextBudget.Policy(
            effectiveWindow = if (config.contextWindow > 0) {
                config.contextWindow
            } else {
                ContextBudget.DEFAULT_WINDOW
            },
            triggerRatio = config.triggerRatio,
            targetRatio = config.targetRatio,
            keepRecentTurns = config.keepRecentTurns,
            keepRecentToolResults = config.keepRecentToolResults,
            offloadChars = config.offloadChars,
        )

        var list = messages.toMutableList()
        var plan = ContextBudget.plan(list, policy, toolsTokens)
        var offloaded = 0

        if (plan.offloadIndices.isNotEmpty() && sessionId.isNotBlank()) {
            for (index in plan.offloadIndices) {
                val message = list.getOrNull(index) ?: continue
                if (message.role != "tool") continue
                val name = toolNameAt(list, index)
                val offloadedFile = artifacts.offload(sessionId, name, message.content) ?: continue
                list[index] = message.copy(content = offloadedFile.placeholder)
                offloaded++
            }
            if (offloaded > 0) {
                plan = ContextBudget.plan(list, policy, toolsTokens)
            }
        }

        if (sessionId.isBlank()) {
            return Step(list, state, offloaded, null)
        }

        val groups = ContextBudget.groupIntoTurns(list)
        var compactGroups = plan.compactGroups
        if (force && compactGroups <= 0) {
            compactGroups = if (groups.size > config.keepRecentTurns) 1 else 0
        }
        if (compactGroups <= 0) {
            return Step(list, state, offloaded, null)
        }

        val compactor = ContextCompactor(artifacts)
        val result = compactor.compact(
            sessionId = sessionId,
            all = list,
            groups = groups,
            compactCount = compactGroups,
            previous = state,
            summarize = summarize,
        ) ?: return Step(list, state, offloaded, null)

        return Step(
            messages = result.messages,
            state = result.state,
            offloaded = offloaded,
            compaction = CompactionEvent(
                removed = result.removed,
                summaryTokens = result.state.summaryTokens,
                archivePath = result.archivePath,
                fallback = result.usedFallback,
            ),
        )
    }

    /** 从 assistant 的 tool_calls 里找出这条工具结果对应的工具名，用于文件名。 */
    private fun toolNameAt(messages: List<AgentClient.Message>, index: Int): String {
        val toolCallId = messages[index].toolCallId
        if (toolCallId.isEmpty()) return "tool"
        for (i in index - 1 downTo 0) {
            val calls = messages[i].toolCalls ?: continue
            for (c in 0 until calls.length()) {
                val row = calls.optJSONObject(c) ?: continue
                if (row.optString("id") == toolCallId) {
                    return row.optJSONObject("function")?.optString("name").orEmpty().ifEmpty { "tool" }
                }
            }
        }
        return "tool"
    }
}
