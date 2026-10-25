package com.wbhub.app.agent

import android.content.Context
import com.wbhub.app.storage.AppStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistence for the agent page.
 *
 * Two things survive a restart: the settings that shape a request, and the
 * conversations themselves. Settings live in shared preferences because they
 * are a handful of scalars; conversations are files because they grow and are
 * one per session rather than one per key.
 */
class AgentStore(context: Context) {

    private val prefs = AppStorage.prefs(context, PREFS)
    /** 提示词单独放在私有目录：共享 JSON 可被外部改写，不能作为系统指令来源。 */
    private val promptPrefs = AppStorage.privatePrefs(context, PROMPT_PREFS)
    private val dir = AppStorage.sessions(context)

    /** One saved conversation. */
    data class Session(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<AgentClient.Message>,
        /** The visible transcript, so a reopened session renders as it did. */
        val entries: List<StoredEntry>,
        /** 已经压缩掉的历史摘要；旧会话没有这个字段时为空状态。 */
        val compaction: CompactionState = CompactionState(),
    )

    /** A transcript line as stored; the role is kept by name for stability. */
    data class StoredEntry(val role: String, val text: String)

    // ------------------------------------------------------------------ //
    // Settings
    // ------------------------------------------------------------------ //

    fun modelId(): String = prefs.getString(KEY_MODEL, "").orEmpty()

    fun setModelId(value: String) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun effort(): String = prefs.getString(KEY_EFFORT, "").orEmpty()

    fun setEffort(value: String) = prefs.edit().putString(KEY_EFFORT, value).apply()

    fun contextWindow(): Int = prefs.getInt(KEY_CONTEXT, 0)

    fun setContextWindow(value: Int) = prefs.edit().putInt(KEY_CONTEXT, value).apply()

    fun exposeRoot(): Boolean = prefs.getBoolean(KEY_ROOT, false)

    fun setExposeRoot(value: Boolean) = prefs.edit().putBoolean(KEY_ROOT, value).apply()

    fun approval(): ApprovalMode = ApprovalMode.fromName(prefs.getString(KEY_APPROVAL, null))

    fun setApproval(value: ApprovalMode) = prefs.edit().putString(KEY_APPROVAL, value.name).apply()

    fun maxRounds(): Int = prefs.getInt(KEY_MAX_ROUNDS, DEFAULT_MAX_ROUNDS).coerceIn(1, 1000)

    fun setMaxRounds(value: Int) =
        prefs.edit().putInt(KEY_MAX_ROUNDS, value.coerceIn(1, 1000)).apply()

    // ------------------------------------------------------------------ //
    // Context compaction
    // ------------------------------------------------------------------ //

    fun compactionEnabled(): Boolean = prefs.getBoolean(KEY_COMPACT_ENABLED, true)

    fun setCompactionEnabled(value: Boolean) =
        prefs.edit().putBoolean(KEY_COMPACT_ENABLED, value).apply()

    /** 触发压缩的窗口占比，30%–95%。 */
    fun compactTrigger(): Int = prefs.getInt(KEY_COMPACT_TRIGGER, 70).coerceIn(30, 95)

    fun setCompactTrigger(value: Int) =
        prefs.edit().putInt(KEY_COMPACT_TRIGGER, value.coerceIn(30, 95)).apply()

    /** 压缩时保留最近多少个用户轮次。 */
    fun compactKeepTurns(): Int = prefs.getInt(KEY_COMPACT_KEEP, 6).coerceIn(1, 30)

    fun setCompactKeepTurns(value: Int) =
        prefs.edit().putInt(KEY_COMPACT_KEEP, value.coerceIn(1, 30)).apply()

    /** 工具输出超过多少字符就落盘。 */
    fun offloadChars(): Int = prefs.getInt(KEY_OFFLOAD_CHARS, 32_000).coerceIn(2_000, 1_000_000)

    fun setOffloadChars(value: Int) =
        prefs.edit().putInt(KEY_OFFLOAD_CHARS, value.coerceIn(2_000, 1_000_000)).apply()

    fun workDir(): String = prefs.getString(KEY_WORKDIR, "").orEmpty()

    fun setWorkDir(value: String) = prefs.edit().putString(KEY_WORKDIR, value).apply()

    // ------------------------------------------------------------------ //
    // Global instructions
    // ------------------------------------------------------------------ //

    /** 用户自定义的全局指令；内置基线不存这里，见 SystemPrompts。 */
    fun systemPrompt(): String = promptPrefs.getString(KEY_SYSTEM_PROMPT, "").orEmpty()

    fun setSystemPrompt(value: String) =
        promptPrefs.edit().putString(KEY_SYSTEM_PROMPT, value.take(SystemPrompts.MAX_USER_CHARS)).apply()

    fun systemPromptEnabled(): Boolean = promptPrefs.getBoolean(KEY_SYSTEM_PROMPT_ENABLED, true)

    fun setSystemPromptEnabled(value: Boolean) =
        promptPrefs.edit().putBoolean(KEY_SYSTEM_PROMPT_ENABLED, value).apply()

    /** 当前生效的内置基线；没有自定义时返回默认文本。 */
    fun systemBaseline(): String =
        promptPrefs.getString(KEY_SYSTEM_BASELINE, "").orEmpty().trim()
            .ifEmpty { SystemPrompts.BASELINE }

    /** 传空串或默认文本即恢复默认。 */
    fun setSystemBaseline(value: String) {
        val clipped = value.trim().take(SystemPrompts.MAX_BASELINE_CHARS)
        if (clipped.isEmpty() || clipped == SystemPrompts.BASELINE) {
            promptPrefs.edit().remove(KEY_SYSTEM_BASELINE).apply()
        } else {
            promptPrefs.edit().putString(KEY_SYSTEM_BASELINE, clipped).apply()
        }
    }

    /** 同时运行的子代理数量，1–4。 */
    fun subagentConcurrency(): Int = prefs.getInt(
        KEY_SUBAGENT_CONCURRENCY,
        SubagentLimits.DEFAULT_CONCURRENT,
    ).coerceIn(SubagentLimits.MIN_CONCURRENT, SubagentLimits.MAX_CONCURRENT)

    fun setSubagentConcurrency(value: Int) = prefs.edit()
        .putInt(
            KEY_SUBAGENT_CONCURRENCY,
            value.coerceIn(SubagentLimits.MIN_CONCURRENT, SubagentLimits.MAX_CONCURRENT),
        )
        .apply()

    // ------------------------------------------------------------------ //
    // Long-term memory
    // ------------------------------------------------------------------ //

    fun memoryEnabled(): Boolean = prefs.getBoolean(KEY_MEMORY_ENABLED, false)

    fun setMemoryEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    fun memoryTopK(): Int = prefs.getInt(KEY_MEMORY_TOP_K, 5).coerceIn(1, 10)

    fun setMemoryTopK(value: Int) =
        prefs.edit().putInt(KEY_MEMORY_TOP_K, value.coerceIn(1, 10)).apply()

    /** 召回最低分，存 0–100 的百分数，避免浮点设置的精度问题。 */
    fun memoryMinScore(): Int = prefs.getInt(KEY_MEMORY_MIN_SCORE, 30).coerceIn(0, 100)

    fun setMemoryMinScore(value: Int) =
        prefs.edit().putInt(KEY_MEMORY_MIN_SCORE, value.coerceIn(0, 100)).apply()

    fun activeSessionId(): String = prefs.getString(KEY_ACTIVE_SESSION, "").orEmpty()

    fun setActiveSessionId(value: String) = prefs.edit().putString(KEY_ACTIVE_SESSION, value).apply()

    // ------------------------------------------------------------------ //
    // Sessions
    // ------------------------------------------------------------------ //

    /** Every session, newest first. */
    fun sessions(): List<Session> = dir.listFiles { file -> file.extension == "json" }
        ?.mapNotNull { file -> runCatching { parse(file.readText(), file.nameWithoutExtension) }.getOrNull() }
        ?.sortedByDescending { it.updatedAt }
        .orEmpty()

    fun load(id: String): Session? {
        val safe = safeId(id) ?: return null
        val file = File(dir, "$safe.json")
        if (!file.exists()) return null
        return runCatching { parse(file.readText(), safe) }.getOrNull()
    }

    /** 会话 id 只允许字母数字下划线短横线，防止被构造的 JSON 带出目录。 */
    private fun safeId(id: String): String? =
        id.takeIf { SESSION_ID.matches(it) }

    fun save(session: Session) {
        val document = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("updatedAt", session.updatedAt)
            put("entries", JSONArray().apply {
                session.entries.forEach { entry ->
                    put(JSONObject().apply { put("role", entry.role); put("text", entry.text) })
                }
            })
            put("messages", JSONArray().apply {
                session.messages.forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                        if (message.toolCallId.isNotEmpty()) put("toolCallId", message.toolCallId)
                        message.toolCalls?.let { put("toolCalls", it) }
                    })
                }
            })
            if (session.compaction.compactedMessages > 0 || session.compaction.summary.isNotBlank()) {
                put(
                    "compaction",
                    JSONObject().apply {
                        put("summary", session.compaction.summary)
                        put("compactedMessages", session.compaction.compactedMessages)
                        put("compactedAt", session.compaction.compactedAt)
                        put("originalTokens", session.compaction.originalTokens)
                        put("summaryTokens", session.compaction.summaryTokens)
                        put("archives", JSONArray(session.compaction.archives))
                    },
                )
            }
        }
        val safe = safeId(session.id) ?: return
        runCatching { AppStorage.writeAtomically(File(dir, "$safe.json"), document.toString()) }
    }

    fun delete(id: String) {
        val safe = safeId(id) ?: return
        runCatching { File(dir, "$safe.json").delete() }
        // 会话的 artifacts / archive 与它同生共死，避免留下孤立的大文件。
        runCatching { File(dir, safe).deleteRecursively() }
    }

    /**
     * A title for the session list.
     *
     * The first user line is the most recognisable name a conversation has, so
     * it is used as-is and clipped rather than asking the model to summarise.
     */
    fun titleFor(prompt: String): String =
        prompt.lineSequence().firstOrNull()?.trim().orEmpty().take(40).ifEmpty { "新会话" }

    /**
     * 旧版本在请求失败时也把用户消息写进历史，重试就会出现连续的重复 prompt。
     * 读取时折叠掉，避免同一句话被重复发送给上游。
     */
    private fun List<AgentClient.Message>.dedupeRepeatedPrompts(): List<AgentClient.Message> {
        val result = ArrayList<AgentClient.Message>(size)
        for (message in this) {
            val previous = result.lastOrNull()
            if (message.role == "user" && previous?.role == "user" && previous.content == message.content) {
                continue
            }
            result += message
        }
        return result
    }

    private fun parse(text: String, fallbackId: String): Session {
        val root = JSONObject(text)
        val entries = root.optJSONArray("entries")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                StoredEntry(row.optString("role"), row.optString("text"))
            }
        }.orEmpty()
        val messages = root.optJSONArray("messages")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                AgentClient.Message(
                    role = row.optString("role"),
                    content = row.optString("content"),
                    toolCalls = row.optJSONArray("toolCalls"),
                    toolCallId = row.optString("toolCallId"),
                )
            }
        }.orEmpty().dedupeRepeatedPrompts()
        val compaction = root.optJSONObject("compaction")?.let { row ->
            CompactionState(
                summary = row.optString("summary"),
                compactedMessages = row.optInt("compactedMessages", 0),
                compactedAt = row.optLong("compactedAt", 0),
                archives = row.optJSONArray("archives")?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.orEmpty(),
                originalTokens = row.optInt("originalTokens", 0),
                summaryTokens = row.optInt("summaryTokens", 0),
            )
        } ?: CompactionState()
        val storedId = root.optString("id")
        return Session(
            id = storedId.takeIf { SESSION_ID.matches(it) } ?: fallbackId,
            title = root.optString("title").ifEmpty { "会话" },
            updatedAt = root.optLong("updatedAt"),
            messages = messages,
            entries = entries,
            compaction = compaction,
        )
    }

    private companion object {
        val SESSION_ID = Regex("[A-Za-z0-9_-]{1,64}")
        const val PREFS = "wb-agent"
        const val PROMPT_PREFS = "wb-agent-prompts"
        const val KEY_MODEL = "model_id"
        const val KEY_EFFORT = "effort"
        const val KEY_CONTEXT = "context_window"
        const val KEY_ROOT = "expose_root"
        const val KEY_APPROVAL = "approval"
        const val KEY_MAX_ROUNDS = "max_rounds"
        const val DEFAULT_MAX_ROUNDS = 100
        const val KEY_WORKDIR = "work_dir"
        const val KEY_SYSTEM_PROMPT = "system_prompt"
        const val KEY_SYSTEM_PROMPT_ENABLED = "system_prompt_enabled"
        const val KEY_SYSTEM_BASELINE = "system_baseline"
        const val KEY_SUBAGENT_CONCURRENCY = "subagent_concurrency"
        const val KEY_ACTIVE_SESSION = "active_session"
        const val KEY_COMPACT_ENABLED = "compact_enabled"
        const val KEY_COMPACT_TRIGGER = "compact_trigger"
        const val KEY_COMPACT_KEEP = "compact_keep_turns"
        const val KEY_OFFLOAD_CHARS = "offload_chars"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_MEMORY_TOP_K = "memory_top_k"
        const val KEY_MEMORY_MIN_SCORE = "memory_min_score"
    }
}
