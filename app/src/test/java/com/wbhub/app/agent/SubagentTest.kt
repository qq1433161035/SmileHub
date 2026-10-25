package com.wbhub.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentTest {

    @Test
    fun parsesObjectTasksAndFallsBackTitle() {
        val tasks = subagentTasksFrom(
            listOf(
                mapOf("title" to "入口", "instruction" to "读 AndroidManifest.xml"),
                mapOf("instruction" to "读 data.json"),
            ),
        )
        assertEquals(2, tasks.size)
        assertEquals("入口", tasks[0].title)
        assertEquals("任务 2", tasks[1].title)
        assertEquals("读 data.json", tasks[1].instruction)
    }

    @Test
    fun acceptsSingleStringAndSkipsBlank() {
        assertEquals(1, subagentTasksFrom("  看看 lib 目录  ").size)
        assertTrue(subagentTasksFrom(listOf("", "   ", 42, null)).isEmpty())
    }

    @Test
    fun capsTasksAndInstructionLength() {
        val many = (1..10).map { mapOf("title" to "t$it", "instruction" to "x".repeat(5000)) }
        val tasks = subagentTasksFrom(many)
        assertEquals(SubagentLimits.MAX_TASKS, tasks.size)
        assertEquals(SubagentLimits.MAX_INSTRUCTION_CHARS, tasks[0].instruction.length)
    }

    @Test
    fun rejectsNonListValues() {
        assertTrue(subagentTasksFrom(null).isEmpty())
        assertTrue(subagentTasksFrom(mapOf("instruction" to "x")).isEmpty())
    }
}
