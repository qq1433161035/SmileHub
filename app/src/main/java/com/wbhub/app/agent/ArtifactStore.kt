package com.wbhub.app.agent

import android.content.Context
import com.wbhub.app.storage.AppStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 会话的磁盘旁路：工具输出完整版（artifacts）与被压缩的历史原文（archive）。
 *
 * 上下文里只留占位符或摘要，原文永远可以在磁盘上找回来；这正是不做有损压缩
 * 也能保证细节不丢的原因。
 */
class ArtifactStore(context: Context) {

    private val sessions = AppStorage.sessions(context)

    data class Offloaded(val path: String, val bytes: Long, val placeholder: String)

    /** 把一条过大的工具结果写到磁盘，并返回可放进上下文的占位符。 */
    fun offload(sessionId: String, toolName: String, text: String): Offloaded? {
        if (sessionId.isBlank() || text.isBlank()) return null
        val dir = File(sessionDir(sessionId), "artifacts").apply { mkdirs() }
        val safeName = toolName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40).ifEmpty { "tool" }
        val file = File(dir, "$safeName-${System.currentTimeMillis()}.txt")
        return runCatching {
            file.writeText(text)
            prune(dir)
            val head = text.lineSequence().take(12).joinToString("\n").take(2_000)
            val sizeKb = file.length() / 1024.0
            val placeholder = buildString {
                appendLine(ContextBudget.OFFLOAD_MARK)
                appendLine("summary: ${firstLine(text)}")
                append("artifact: ${file.absolutePath} (")
                append(String.format("%.1f", sizeKb))
                appendLine(" KB)")
                appendLine("head:")
                append(head)
                if (text.length > head.length) append("\n…")
                appendLine()
                append("hint: 需要完整内容时用 read(path=\"${file.absolutePath}\")")
            }
            Offloaded(file.absolutePath, file.length(), placeholder)
        }.getOrNull()
    }

    /** 压缩前把原始消息整段落盘；失败时调用方应放弃本次压缩。 */
    fun archive(sessionId: String, messages: List<AgentClient.Message>): String? {
        if (sessionId.isBlank() || messages.isEmpty()) return null
        val dir = File(sessionDir(sessionId), "archive").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.json")
        return runCatching {
            val array = JSONArray()
            messages.forEach { message ->
                array.put(
                    JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        if (message.toolCallId.isNotEmpty()) put("toolCallId", message.toolCallId)
                        message.toolCalls?.let { put("toolCalls", it) }
                    },
                )
            }
            file.writeText(array.toString())
            pruneArchives(dir)
            file.absolutePath
        }.getOrNull()
    }

    /** 删除会话时把它的旁路目录一起清掉。 */
    fun deleteSession(sessionId: String) {
        if (sessionId.isBlank()) return
        runCatching { File(sessions, sessionId).deleteRecursively() }
    }

    private fun sessionDir(sessionId: String): File =
        File(sessions, sessionId).apply { mkdirs() }

    /** 单个会话的 artifacts 最多保留 200MB，超了从最老的开始删。 */
    private fun prune(dir: File) {
        runCatching {
            val files = dir.listFiles { file -> file.isFile }?.sortedBy { it.name }.orEmpty()
            var total = files.sumOf { it.length() }
            var index = 0
            while (total > MAX_ARTIFACT_BYTES && index < files.size) {
                total -= files[index].length()
                files[index].delete()
                index++
            }
        }
    }

    /** 每个会话保留最近 20 份原文归档，更早的删掉。 */
    private fun pruneArchives(dir: File) {
        runCatching {
            val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (files.size > MAX_ARCHIVES) {
                files.take(files.size - MAX_ARCHIVES).forEach { it.delete() }
            }
        }
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(200).orEmpty()

    private companion object {
        const val MAX_ARTIFACT_BYTES = 200L * 1024 * 1024
        const val MAX_ARCHIVES = 20
    }
}
