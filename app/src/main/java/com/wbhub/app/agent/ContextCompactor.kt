package com.wbhub.app.agent

import com.wbhub.app.agent.ContextBudget.SUMMARY_PREFIX

/** 一次压缩后留在会话里的状态，随会话 JSON 一起保存。 */
data class CompactionState(
    val summary: String = "",
    val compactedMessages: Int = 0,
    val compactedAt: Long = 0L,
    val archives: List<String> = emptyList(),
    val originalTokens: Int = 0,
    val summaryTokens: Int = 0,
)

/**
 * 把最老的若干轮次变成一条结构化摘要。
 *
 * 原文先归档，归档失败就放弃压缩；摘要失败则降级为“只保留最近轮次 + 已有摘要”，
 * 保证压缩永远不会阻塞用户这一轮提问。
 */
class ContextCompactor(private val store: ArtifactStore) {

    data class Result(
        val messages: List<AgentClient.Message>,
        val state: CompactionState,
        val removed: Int,
        val usedFallback: Boolean,
        val archivePath: String?,
    )

    fun compact(
        sessionId: String,
        all: List<AgentClient.Message>,
        groups: List<IntRange>,
        compactCount: Int,
        previous: CompactionState,
        summarize: (String) -> String?,
    ): Result? {
        if (compactCount <= 0 || compactCount >= groups.size) return null
        val removedIndices = groups.take(compactCount).flatMap { it }
        if (removedIndices.isEmpty()) return null
        val removed = removedIndices.map { all[it] }
        val keep = groups.drop(compactCount).flatMap { it }.map { all[it] }

        val archive = store.archive(sessionId, removed) ?: return null
        val originalTokens = ContextBudget.estimateMessages(removed)

        val generated = runCatching { summarize(buildPrompt(previous.summary, removed)) }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val summary = generated ?: previous.summary
        val usedFallback = generated == null
        val head = if (summary.isNotBlank()) {
            "$SUMMARY_PREFIX\n$summary"
        } else {
            "$SUMMARY_PREFIX\n更早的 ${removed.size} 条消息已省略（摘要生成失败）；需要细节时请说明要回溯的内容。"
        }
        val headMessage = AgentClient.Message(role = "user", content = head)
        val messages = listOf(headMessage) + keep

        return Result(
            messages = messages,
            state = CompactionState(
                summary = summary,
                compactedMessages = previous.compactedMessages + removed.size,
                compactedAt = System.currentTimeMillis(),
                archives = (previous.archives + archive).takeLast(20),
                originalTokens = originalTokens,
                summaryTokens = ContextBudget.estimateTokens(head),
            ),
            removed = removed.size,
            usedFallback = usedFallback,
            archivePath = archive,
        )
    }

    private fun buildPrompt(previousSummary: String, messages: List<AgentClient.Message>): String =
        buildString {
            appendLine("你是上下文压缩器。请把下面的对话整理成结构化摘要。")
            appendLine("要求：")
            appendLine("1. 保留任务目标、当前进度、关键产物路径、包名/项目 id、失败原因和已排除的方案。")
            appendLine("2. 路径、命令、类名、包名必须原样保留，不要翻译。")
            appendLine("3. 只输出下面的结构，不要解释：")
            appendLine()
            appendLine("【历史摘要】")
            appendLine("目标：")
            appendLine("- ...")
            appendLine("已完成：")
            appendLine("- ...")
            appendLine("关键产物：")
            appendLine("- ...")
            appendLine("关键事实 / 约束：")
            appendLine("- ...")
            appendLine("未完成 / 下一步：")
            appendLine("- ...")
            appendLine()
            if (previousSummary.isNotBlank()) {
                appendLine("## 已有摘要（请在此基础上合并更新）")
                appendLine(previousSummary.take(8_000))
                appendLine()
            }
            appendLine("## 新增历史")
            messages.forEach { message ->
                val content = message.content.trim()
                if (content.isEmpty()) return@forEach
                if (content.startsWith(SUMMARY_PREFIX)) return@forEach
                appendLine("[${message.role}]")
                appendLine(content.take(MAX_MESSAGE_CHARS))
                val tools = toolNames(message)
                if (tools.isNotEmpty()) appendLine("(调用了工具: $tools)")
                appendLine()
            }
        }

    private fun toolNames(message: AgentClient.Message): String {
        val calls = message.toolCalls ?: return ""
        return buildList {
            for (i in 0 until calls.length()) {
                val name = calls.optJSONObject(i)
                    ?.optJSONObject("function")
                    ?.optString("name")
                    .orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }.distinct().joinToString(", ")
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 4_000
    }
}
