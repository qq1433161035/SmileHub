package com.wbhub.app.tools

/** Where a registered tool came from; only external tools are reloadable. */
enum class ToolSource { BUILTIN, EXTERNAL }

/**
 * The set of tools the model can call.
 *
 * Built-ins are registered once at startup. External tools live in
 * `/storage/emulated/0/WBHub/tools` and are replaced as a group whenever the
 * directory is rescanned, so adding or editing one does not need a restart.
 */
class ToolRegistry {

    class RegisteredTool(
        val spec: ToolSpec,
        val source: ToolSource,
        val run: (Map<String, Any?>) -> ToolResult,
    )

    private val tools = LinkedHashMap<String, RegisteredTool>()

    @Synchronized
    fun register(spec: ToolSpec, run: (Map<String, Any?>) -> ToolResult) {
        tools[spec.name] = RegisteredTool(spec, ToolSource.BUILTIN, run)
    }

    @Synchronized
    fun register(
        spec: ToolSpec,
        run: (Map<String, Any?>) -> ToolResult,
        source: ToolSource,
    ) {
        tools[spec.name] = RegisteredTool(spec, source, run)
    }

    @Synchronized
    fun find(name: String): RegisteredTool? = tools[name]

    @Synchronized
    fun definitions(): List<Map<String, Any>> = tools.values.map { it.spec.definition() }

    @Synchronized
    fun specs(): List<ToolSpec> = tools.values.map { it.spec }

    /** Swaps every tool of one source; used when the external folder changes. */
    @Synchronized
    fun replaceSource(source: ToolSource, replacements: List<RegisteredTool>) {
        tools.entries.removeAll { it.value.source == source }
        replacements.forEach { tools[it.spec.name] = it }
    }

    @Synchronized
    fun count(source: ToolSource): Int = tools.values.count { it.source == source }
}
