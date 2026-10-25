package com.wbhub.app.agent

/** 父代理交给某个子代理的一个独立任务。 */
data class SubagentTask(val title: String, val instruction: String)

/** 子代理执行结果；[text] 已按长度截断，可直接作为工具结果回给父代理。 */
data class SubagentReport(val text: String, val ok: Boolean)

/** 子代理执行器。实现方负责并发、取消与超时，调用线程会被阻塞到全部结束。 */
fun interface SubagentRunner {
    fun run(
        tasks: List<SubagentTask>,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): SubagentReport
}

object SubagentLimits {
    /** 一次调用最多拆几个子任务。 */
    const val MAX_TASKS = 4

    /** 默认同时运行数。 */
    const val DEFAULT_CONCURRENT = 3

    /** 用户可选的并发数上限；同时运行多余任务会排队。 */
    const val MAX_CONCURRENT = 4

    const val MIN_CONCURRENT = 1

    /** 子代理自己的工具轮次上限。 */
    const val MAX_ROUNDS = 8

    /** 单个子代理的最长运行时间。 */
    const val TIMEOUT_MS = 5 * 60_000L

    const val MAX_TITLE_CHARS = 80
    const val MAX_INSTRUCTION_CHARS = 4_000

    /** 单个子代理报告的上限；多个报告合起来还有总上限。 */
    const val MAX_REPORT_CHARS = 6_000
    const val MAX_TOTAL_REPORT_CHARS = 12_000
}

/**
 * 子代理允许使用的工具。
 *
 * P0 只开放只读能力：子代理不能写文件、执行 shell/python 或改动设备，
 * 因此多个子代理并发时不会互相踩写操作，也不会冒出并发审批弹窗。
 * 需要修改时由父代理根据报告自己执行。
 */
val SUBAGENT_READ_ONLY_TOOLS: Set<String> = setOf(
    "read",
    "view_image",
    "memory_search",
    "memory_list",
)

/** 追加在内置基线之后的子代理工作规则。 */
val SUBAGENT_RULES: String = """
[子代理]
你只负责一个独立的子任务，没有写权限，也不能执行 shell 或修改设备。
用 read / view_image 核对事实，用 memory_search 查已有结论；大文件用 offset / limit 分段读。
只输出与子任务直接相关的结果：先给结论，再列关键依据（路径、行号、输出片段）。
不要复述任务原文，不要提出后续计划；信息不足时明确写出缺什么。
""".trimIndent()

/**
 * 解析模型传来的 tasks。
 *
 * 兼容两种写法：对象数组（推荐）或单个字符串；非法项直接跳过，最多保留
 * [SubagentLimits.MAX_TASKS] 个。解析放在纯函数里，方便单测。
 */
fun subagentTasksFrom(value: Any?): List<SubagentTask> {
    val raw: List<*> = when (value) {
        is List<*> -> value
        is String -> listOf(value)
        else -> return emptyList()
    }
    val tasks = ArrayList<SubagentTask>(raw.size)
    for ((index, item) in raw.withIndex()) {
        if (tasks.size >= SubagentLimits.MAX_TASKS) break
        val title: String
        val instruction: String?
        when (item) {
            is Map<*, *> -> {
                title = (item["title"] as? String).orEmpty()
                instruction = item["instruction"] as? String
            }
            is String -> {
                title = ""
                instruction = item
            }
            else -> continue
        }
        val text = instruction?.trim().orEmpty()
        if (text.isEmpty()) continue
        val label = title.lineSequence().firstOrNull().orEmpty().trim()
            .take(SubagentLimits.MAX_TITLE_CHARS)
            .ifEmpty { "任务 ${index + 1}" }
        tasks += SubagentTask(label, text.take(SubagentLimits.MAX_INSTRUCTION_CHARS))
    }
    return tasks
}
