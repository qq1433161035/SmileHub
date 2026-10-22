package com.wbhub.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wbhub.app.agent.AgentClient
import com.wbhub.app.agent.AgentStore
import com.wbhub.app.agent.ArtifactStore
import com.wbhub.app.agent.CompactionState
import com.wbhub.app.agent.ContextBudget
import com.wbhub.app.agent.ContextEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 上下文压缩的端到端检查：不请求模型，用固定的摘要替身走完
 * offload → 归档 → 摘要 → 重建消息 的完整路径。
 */
@RunWith(AndroidJUnit4::class)
class ContextEngineTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun compactsOldTurnsAndKeepsProtocolPairs() {
        val sessionId = "compact-test-${System.currentTimeMillis()}"
        val artifacts = ArtifactStore(context)
        val engine = ContextEngine(
            artifacts = artifacts,
            config = ContextEngine.CompressionConfig(
                enabled = true,
                triggerRatio = 0.5,
                targetRatio = 0.2,
                keepRecentTurns = 2,
                keepRecentToolResults = 1,
                contextWindow = 2_000,
                offloadChars = 500,
            ),
        )

        try {
            val messages = buildTranscript(10)
            val step = engine.prepare(
                messages = messages,
                sessionId = sessionId,
                toolsTokens = 0,
                state = CompactionState(),
                summarize = { "【历史摘要】\n目标：验证压缩" },
            )

            assertTrue("expected offload, got ${step.offloaded}", step.offloaded > 0)
            assertNotNull("expected compaction", step.compaction)
            assertTrue(step.compaction!!.removed > 0)
            assertTrue(
                "first message should be the summary",
                step.messages.first().content.startsWith(ContextBudget.SUMMARY_PREFIX),
            )
            assertTrue("should shrink", step.messages.size < messages.size)
            assertTrue("summary tokens recorded", step.state.summaryTokens > 0)

            assertNoOrphanToolMessages(step.messages)

            val archive = step.compaction.archivePath
            assertTrue("archive missing: $archive", archive != null && File(archive).isFile)

            val offloadedFile = step.messages
                .firstOrNull { it.content.startsWith(ContextBudget.OFFLOAD_MARK) }
                ?.content
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("artifact: ") }
                ?.removePrefix("artifact: ")
                ?.substringBefore(" (")
            assertTrue(
                "offloaded artifact missing: $offloadedFile",
                offloadedFile != null && File(offloadedFile).isFile,
            )
        } finally {
            artifacts.deleteSession(sessionId)
        }
    }

    @Test
    fun forceCompactsEvenBelowThreshold() {
        val sessionId = "compact-force-${System.currentTimeMillis()}"
        val artifacts = ArtifactStore(context)
        val engine = ContextEngine(
            artifacts = artifacts,
            config = ContextEngine.CompressionConfig(
                enabled = true,
                triggerRatio = 0.7,
                targetRatio = 0.45,
                keepRecentTurns = 2,
                contextWindow = 1_000_000,
                offloadChars = 500,
            ),
        )

        try {
            val step = engine.prepare(
                messages = buildTranscript(4),
                sessionId = sessionId,
                toolsTokens = 0,
                state = CompactionState(),
                force = true,
                summarize = { "【历史摘要】\n目标：手动压缩" },
            )
            assertNotNull("manual compaction should still run", step.compaction)
            assertTrue(step.compaction!!.removed > 0)
            assertTrue(step.messages.first().content.startsWith(ContextBudget.SUMMARY_PREFIX))
        } finally {
            artifacts.deleteSession(sessionId)
        }
    }

    @Test
    fun repeatedPromptsAreCollapsedOnLoad() {
        val store = AgentStore(context)
        val id = "dedupe-test-${System.currentTimeMillis()}"
        val prompt = "把 UI 文本替换成哈哈哈"
        try {
            store.save(
                AgentStore.Session(
                    id = id,
                    title = "dedupe",
                    updatedAt = System.currentTimeMillis(),
                    messages = listOf(
                        AgentClient.Message("user", prompt),
                        AgentClient.Message("user", prompt),
                        AgentClient.Message("assistant", "done"),
                    ),
                    entries = emptyList(),
                ),
            )
            val loaded = store.load(id)!!
            assertEquals(
                listOf("user", "assistant"),
                loaded.messages.map { it.role },
            )
        } finally {
            store.delete(id)
        }
    }

    private fun buildTranscript(turns: Int): List<AgentClient.Message> {
        val messages = mutableListOf<AgentClient.Message>()
        for (index in 0 until turns) {
            messages += AgentClient.Message("user", "turn $index " + "u".repeat(2_000))
            messages += AgentClient.Message(
                role = "assistant",
                content = "",
                toolCalls = JSONArray().put(
                    JSONObject().apply {
                        put("id", "call-$index")
                        put("type", "function")
                        put("function", JSONObject().apply { put("name", "bash"); put("arguments", "{}") })
                    },
                ),
            )
            messages += AgentClient.Message("tool", "x".repeat(2_000), toolCallId = "call-$index")
            messages += AgentClient.Message("assistant", "done $index")
        }
        return messages
    }

    /** 每条 tool 消息都必须能对应到前面某个 assistant 的 tool_calls id。 */
    private fun assertNoOrphanToolMessages(messages: List<AgentClient.Message>) {
        val known = mutableSetOf<String>()
        for (message in messages) {
            message.toolCalls?.let { calls ->
                for (i in 0 until calls.length()) {
                    calls.optJSONObject(i)?.optString("id")?.takeIf { it.isNotEmpty() }?.let { known += it }
                }
            }
            if (message.role == "tool") {
                assertTrue(
                    "orphan tool message: ${message.toolCallId}",
                    known.contains(message.toolCallId),
                )
            }
        }
    }
}
