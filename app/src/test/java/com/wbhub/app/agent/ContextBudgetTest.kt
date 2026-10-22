package com.wbhub.app.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {

    private fun user(text: String) = AgentClient.Message(role = "user", content = text)
    private fun assistant(text: String) = AgentClient.Message(role = "assistant", content = text)
    private fun tool(text: String) = AgentClient.Message(role = "tool", content = text, toolCallId = "c1")

    @Test
    fun chineseCostsMoreThanAscii() {
        val ascii = ContextBudget.estimateTokens("x".repeat(160))
        val chinese = ContextBudget.estimateTokens("测".repeat(160))
        assertTrue("chinese=$chinese ascii=$ascii", chinese > ascii)
    }

    @Test
    fun turnsStartAtUserMessagesAndKeepToolPairs() {
        val messages = listOf(
            user("first"),
            assistant(""),
            AgentClient.Message(
                role = "assistant",
                content = "",
                toolCalls = JSONArray().put(
                    JSONObject().apply {
                        put("id", "c1")
                        put("type", "function")
                        put("function", JSONObject().apply { put("name", "bash"); put("arguments", "{}") })
                    },
                ),
            ),
            tool("result"),
            user("second"),
            assistant("done"),
        )

        val groups = ContextBudget.groupIntoTurns(messages)
        assertEquals(listOf(0 until 4, 4 until 6), groups)
    }

    @Test
    fun underThresholdDoesNothing() {
        val messages = listOf(user("short"), assistant("short"))
        val plan = ContextBudget.plan(
            messages,
            ContextBudget.Policy(effectiveWindow = 100_000, triggerRatio = 0.7, keepRecentTurns = 2),
            fixedTokens = 0,
        )
        assertEquals(0, plan.compactGroups)
        assertTrue(plan.offloadIndices.isEmpty())
    }

    @Test
    fun overThresholdCompactsOldestTurnsAndKeepsRecentOnes() {
        val messages = mutableListOf<AgentClient.Message>()
        repeat(12) { index ->
            messages += user("u$index " + "x".repeat(2_000))
            messages += assistant("a$index " + "y".repeat(2_000))
        }
        val policy = ContextBudget.Policy(
            effectiveWindow = 8_000,
            triggerRatio = 0.5,
            targetRatio = 0.2,
            keepRecentTurns = 3,
        )
        val plan = ContextBudget.plan(messages, policy)
        val groups = ContextBudget.groupIntoTurns(messages)

        assertTrue("expected compaction, got ${plan.compactGroups}", plan.compactGroups > 0)
        assertTrue(
            "must keep 3 recent turns, compact=${plan.compactGroups} groups=${groups.size}",
            groups.size - plan.compactGroups >= 3,
        )
    }

    @Test
    fun offloadSkipsRecentToolResultsAndExistingPlaceholders() {
        val messages = listOf(
            tool("a".repeat(500)),
            tool("b".repeat(500)),
            tool(ContextBudget.OFFLOAD_MARK + "\nsummary: old"),
            tool("c".repeat(500)),
        )
        val policy = ContextBudget.Policy(keepRecentToolResults = 1, offloadChars = 100)
        val candidates = ContextBudget.offloadCandidates(messages, policy)

        assertEquals(listOf(0, 1), candidates)
    }
}
