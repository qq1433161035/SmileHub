package com.wbhub.app.agent

/**
 * 系统提示词的拼接规则。
 *
 * 每次用户提问都会重新拼一份，不写进 transcript：改完设置后下一次提问生效，
 * 同一次任务后续的工具轮次沿用同一份，避免中途改变上下文。也不会随着会话保存而重复累积。层级固定为
 *
 * 内置基线 → 用户全局指令 → 长期记忆
 *
 * 记忆段自带“不可信数据”的说明，放在最后可以保证它紧挨着历史消息。
 */
object SystemPrompts {

    /** 用户全局指令的字符上限；超出部分直接截断。 */
    const val MAX_USER_CHARS = 2_000

    /** 内置基线可编辑后的字符上限。 */
    const val MAX_BASELINE_CHARS = 4_000

    /**
     * 默认基线：只描述运行环境与安全边界，不替用户设定人设或输出风格。
     * 设置页可以覆盖并在需要时恢复为这份默认值。
     * 这些规则在代码里另有硬约束（审批、环境守卫、工作目录边界），提示词只是
     * 让模型提前知道，不能作为安全依赖。
     */
    val BASELINE: String = """
        你是一个运行在 Android 设备上的终端助手。
        - 需要读取文件、执行命令或修改代码时，优先调用内置工具确认事实，不要凭猜测下结论。
        - 任务产物只写在工作目录内；设置、数据库、证书等配置目录不要写入任务文件。
        - 删除、覆盖或改动系统设置前先说明影响；无法确认用户意图时先询问。
        - 工具调用失败时，先读错误信息再换方案，不要重复同样的失败调用。
        - 回答简洁，使用用户当前使用的语言。
    """.trimIndent()

    /** 设置页里的一键模板，覆盖最常见的三类诉求。 */
    val TEMPLATES: List<Pair<String, String>> = listOf(
        "逆向分析" to """
            进行逆向分析时：先用静态手段（解包、字符串、调用关系）建立假设，再决定是否动态调试。
            不要修改原始样本；所有解包产物、脚本和笔记都放在工作目录下的独立子目录。
            结论要给出依据（文件路径、行号、命令输出），不确定的地方明确标注。
        """.trimIndent(),
        "输出风格" to """
            回答保持精炼：先给结论，再给必要的步骤或代码。
            长输出只保留关键片段，完整内容写入工作目录并在回答里给出路径。
            代码块标注语言；命令区分只读与有副作用的操作。
        """.trimIndent(),
        "危险操作" to """
            涉及删除、覆盖、格式化、提权或改动系统设置的操作，执行前先用一句话说明对象与后果。
            能先备份就先备份，备份放在工作目录下。
            如果影响范围无法确定，停下来询问，不要自行扩大操作范围。
        """.trimIndent(),
    )

    /**
     * 拼出本次请求的 system 内容。
     *
     * @param userPrompt 用户全局指令，[enabled] 为 false 时忽略。
     * @param memoryPreamble 长期记忆前缀，没有召回时传 null。
     * @param baseline 内置基线；设置页允许覆盖，传默认值即恢复原样。
     */
    fun compose(
        userPrompt: String,
        memoryPreamble: String?,
        enabled: Boolean = true,
        baseline: String = BASELINE,
    ): String {
        val parts = ArrayList<String>(3)
        baseline.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        if (enabled) {
            userPrompt.trim().takeIf { it.isNotEmpty() }?.let { parts += "[全局指令]\n$it" }
        }
        memoryPreamble?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString("\n\n")
    }
}
