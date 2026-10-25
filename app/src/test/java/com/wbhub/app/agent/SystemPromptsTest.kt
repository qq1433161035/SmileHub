package com.wbhub.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptsTest {

    @Test
    fun baselineIsAlwaysPresent() {
        val text = SystemPrompts.compose("", null)
        assertEquals(SystemPrompts.BASELINE, text)
    }

    @Test
    fun disabledPromptKeepsBaselineAndMemory() {
        val text = SystemPrompts.compose("不要输出解释", "[长期记忆] x", enabled = false)
        assertTrue(text.startsWith(SystemPrompts.BASELINE))
        assertFalse(text.contains("不要输出解释"))
        assertTrue(text.endsWith("[长期记忆] x"))
    }

    @Test
    fun orderIsBaselineUserMemory() {
        val text = SystemPrompts.compose("用户约束", "[长期记忆] y")
        val baseline = text.indexOf(SystemPrompts.BASELINE)
        val user = text.indexOf("[全局指令]\n用户约束")
        val memory = text.indexOf("[长期记忆] y")
        assertTrue(baseline >= 0 && user > baseline && memory > user)
    }

    @Test
    fun blankUserPromptAddsNoHeading() {
        assertFalse(SystemPrompts.compose("   ", null).contains("[全局指令]"))
    }
}
