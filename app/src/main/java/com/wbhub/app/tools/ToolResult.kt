package com.wbhub.app.tools

/** A file produced or touched by a tool, registered in the artifact table. */
data class ToolArtifact(
    val id: String,
    val kind: String,
    val path: String,
    val sha256: String,
    val size: Long,
)

/** A decode/build message worth showing back to the model. */
data class ToolDiagnostic(
    val stage: String,
    val severity: String,
    val file: String? = null,
    val line: Int? = null,
    val message: String,
)

/**
 * What a tool hands back.
 *
 * The text is what the model reads; artifacts and diagnostics are carried
 * separately so the UI and the database do not have to parse prose.
 */
data class ToolResult(
    val ok: Boolean,
    val summary: String,
    val text: String = "",
    val artifacts: List<ToolArtifact> = emptyList(),
    val diagnostics: List<ToolDiagnostic> = emptyList(),
) {
    fun render(): String = buildString {
        append(if (ok) "ok" else "failed")
        append(": ")
        append(summary)
        if (text.isNotBlank()) {
            append('\n')
            append(text)
        }
        if (artifacts.isNotEmpty()) {
            append("\nartifacts:")
            artifacts.forEach { artifact ->
                append("\n- ${artifact.kind} ${artifact.path} (${artifact.size} bytes, sha256 ${artifact.sha256.take(16)}…)")
            }
        }
        if (diagnostics.isNotEmpty()) {
            append("\ndiagnostics:")
            diagnostics.take(20).forEach { item ->
                append("\n- [${item.severity}] ${item.stage}: ${item.file.orEmpty()}")
                item.line?.let { append(":$it") }
                append(" ${item.message}")
            }
        }
    }

    companion object {
        fun fail(code: String, message: String) = ToolResult(false, "[$code] $message")

        fun error(code: String, message: String, detail: String = "") =
            ToolResult(false, "[$code] $message", detail)
    }
}
