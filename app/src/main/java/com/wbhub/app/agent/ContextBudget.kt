package com.wbhub.app.agent

/**
 * Token 估算与压缩决策。
 *
 * 不引入 tokenizer：按字符类型做保守估算，压缩阈值本身留了足够余量。这里全是
 * 纯函数，方便单测（见 `app/src/test/.../ContextBudgetTest.kt`）。
 */
object ContextBudget {

    /** 用户没选窗口大小时，按 128k 估算预算。 */
    const val DEFAULT_WINDOW = 128_000

    const val SUMMARY_PREFIX = "[历史摘要]"

    data class Policy(
        val effectiveWindow: Int = DEFAULT_WINDOW,
        /** 超过窗口的这个比例才开始压缩。 */
        val triggerRatio: Double = 0.70,
        /** 压缩后希望降到的比例。 */
        val targetRatio: Double = 0.45,
        /** 最近多少个用户轮次保持原文。 */
        val keepRecentTurns: Int = 6,
        /** 最近多少条工具结果保持原文。 */
        val keepRecentToolResults: Int = 3,
        /** 超过这么多字符的工具结果落盘。 */
        val offloadChars: Int = 32_000,
    )

    data class Plan(
        val estimatedTokens: Int,
        /** 需要替换成占位符的工具消息下标。 */
        val offloadIndices: List<Int>,
        /** 从最老的轮次开始，需要摘要掉的组数。 */
        val compactGroups: Int,
    )

    /**
     * 中文约 1.6 字符/token，英文约 4 字符/token，再给每条消息加固定开销。
     * 偏保守，宁可早一点压缩。
     */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var ascii = 0
        var other = 0
        for (ch in text) {
            if (ch.code < 128) ascii++ else other++
        }
        return (ascii / 4.0 + other / 1.6).toInt()
    }

    fun estimateMessages(messages: List<AgentClient.Message>): Int =
        messages.sumOf { estimateTokens(it.content) + 8 }

    /**
     * 以 user 消息为界分组。assistant(tool_calls) 与随后的 tool 消息天然落在
     * 同一组里，因此按组压缩不会破坏协议的配对关系。
     */
    fun groupIntoTurns(messages: List<AgentClient.Message>): List<IntRange> {
        if (messages.isEmpty()) return emptyList()
        val starts = mutableListOf(0)
        messages.forEachIndexed { index, message ->
            if (index > 0 && message.role == "user") starts += index
        }
        return starts.mapIndexed { position, start ->
            start until (starts.getOrNull(position + 1) ?: messages.size)
        }
    }

    /**
     * @param fixedTokens 工具定义等每轮固定成本，单独计入预算。
     */
    fun plan(
        messages: List<AgentClient.Message>,
        policy: Policy,
        fixedTokens: Int = 0,
    ): Plan {
        val estimated = estimateMessages(messages) + fixedTokens
        val trigger = policy.effectiveWindow * policy.triggerRatio
        val offload = offloadCandidates(messages, policy)

        if (estimated <= trigger) {
            return Plan(estimated, offload, 0)
        }

        val groups = groupIntoTurns(messages)
        val target = policy.effectiveWindow * policy.targetRatio - fixedTokens

        // 只压缩最老的组，且至少给 keepRecentTurns 个轮次留原文。
        val counts = groups.map { group ->
            group.sumOf { estimateTokens(messages[it].content) + 8 }
        }
        var remaining = estimated.toDouble()
        var compact = 0
        val maxCompact = (groups.size - policy.keepRecentTurns).coerceAtLeast(0)
        while (compact < maxCompact && remaining > target) {
            remaining -= counts[compact]
            compact++
        }
        return Plan(estimated, offload, compact)
    }

    /** 旧的、超长的工具结果；最近几条保留原文，避免刚拿到的结果就被移走。 */
    fun offloadCandidates(
        messages: List<AgentClient.Message>,
        policy: Policy,
    ): List<Int> {
        val toolIndices = messages.indices.filter { messages[it].role == "tool" }
        val keep = toolIndices.takeLast(policy.keepRecentToolResults).toSet()
        return toolIndices.filter { index ->
            index !in keep &&
                messages[index].content.length > policy.offloadChars &&
                !messages[index].content.startsWith(OFFLOAD_MARK)
        }
    }

    const val OFFLOAD_MARK = "[tool output offloaded]"
}
