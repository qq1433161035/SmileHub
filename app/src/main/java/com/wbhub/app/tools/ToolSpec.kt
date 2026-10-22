package com.wbhub.app.tools

/** How much damage a tool call can do; drives the approval policy. */
enum class ToolRisk { READ, WRITE, EXEC, SENSITIVE }

/**
 * One tool the model can call.
 *
 * The definition is kept as the OpenAI function schema, while the risk and
 * limits stay on the Kotlin side so they cannot be talked around by the model.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val risk: ToolRisk,
    val properties: Map<String, Any> = emptyMap(),
    val required: List<String> = emptyList(),
    val timeoutSeconds: Int = 120,
    val maxOutputBytes: Int = 30_000,
) {
    init {
        // OpenAI/Anthropic 的工具名只允许字母、数字、下划线和短横线；
        // 带点号的名称会让上游直接 400，整个对话都发不出去。
        require(NAME_PATTERN.matches(name)) {
            "tool name must match ${NAME_PATTERN.pattern}: $name"
        }
    }

    fun definition(): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to mapOf(
                "type" to "object",
                "properties" to properties,
                "required" to required,
            ),
        ),
    )

    private companion object {
        val NAME_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")
    }
}

internal fun stringProp(description: String) = mapOf("type" to "string", "description" to description)

internal fun intProp(description: String) = mapOf("type" to "integer", "description" to description)

internal fun boolProp(description: String) = mapOf("type" to "boolean", "description" to description)
