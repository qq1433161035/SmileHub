package com.wbhub.app.agent.memory

import android.util.Log
import com.wbhub.app.agent.AgentStore
import java.util.concurrent.Executors

/**
 * 记忆的写入端：抽取、过滤、去重、冲突替代与向量化。
 *
 * 自动索引在单线程上异步执行，绝不占用 Agent 轮次；工具调用走同步路径，
 * 因为模型需要立刻拿到写入结果。所有多步写入都在同一个 SQLite 事务里。
 */
class MemoryIndexer(
    private val store: MemoryStore,
    /** 第二道开关：关闭后自动索引与工具写入都不再落库。 */
    private val enabled: () -> Boolean = { true },
) {

    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "memory-io").apply { isDaemon = true }
    }

    /** 压缩完成后调用；失败只记日志，不影响本轮对话。 */
    fun indexCompaction(
        sessionId: String,
        projectId: String,
        summary: String,
        archivePath: String?,
        sourceUpdatedAt: Long = 0L,
    ) {
        if (!enabled() || summary.isBlank()) return
        io.execute {
            runCatching {
                store.purgeExpired()
                indexCompactionNow(sessionId, projectId, summary, archivePath, sourceUpdatedAt)
            }.onFailure { Log.w(TAG, "index compaction failed", it) }
        }
    }

    fun indexCompactionNow(
        sessionId: String,
        projectId: String,
        summary: String,
        archivePath: String?,
        sourceUpdatedAt: Long = 0L,
    ): Int {
        val drafts = MemoryExtractor.fromSummary(
            summary, sessionId, projectId, archivePath.orEmpty(), sourceUpdatedAt,
        )
        var written = 0
        for (draft in drafts) {
            when (upsert(draft).action) {
                ACTION_NEW, ACTION_MERGED, ACTION_SUPERSEDED -> written++
                else -> Unit
            }
        }
        return written
    }

    fun saveManual(
        text: String,
        type: MemoryType,
        scope: MemoryScope,
        projectId: String,
        sessionId: String,
        tags: String,
        importance: Float,
    ): MemoryWriteResult {
        if (!enabled()) return MemoryWriteResult(ACTION_UNAVAILABLE, -1)
        if (text.length > MAX_TEXT) {
            return MemoryWriteResult(ACTION_TOO_LONG, -1)
        }
        val draft = MemoryDraft(
            type = type,
            scope = scope,
            projectId = projectId,
            sessionId = sessionId,
            subject = MemoryExtractor.subjectOf(text.trim()),
            text = text.trim(),
            sourceKind = "manual",
            sourcePath = "",
            tags = tags,
            importance = importance.coerceIn(0f, 1f),
        )
        return upsert(draft)
    }

    fun update(id: Long, text: String): MemoryUpdateResult = updateMemory(store, id, text)

    fun forget(id: Long): Boolean = store.delete(id)

    fun cleanup() {
        if (!enabled()) return
        io.execute {
            runCatching {
                store.purgeExpired()
                store.enforceSizeLimit()
            }.onFailure { Log.w(TAG, "memory cleanup failed", it) }
        }
    }

    fun clearAll() {
        io.execute { runCatching { store.clear() } }
    }

    /**
     * 从最近若干个会话的压缩摘要回填索引。
     *
     * 必须按时间升序处理：否则旧会话后写入，会把新结论标成被替代。
     * 返回本次实际写入（新增/合并/替代）的条数。
     */
    fun rebuild(sessions: List<AgentStore.Session>, projectId: String = ""): Int {
        var written = 0
        runCatching { store.purgeExpired() }
        sessions.sortedBy { it.updatedAt }.forEach { session ->
            val summary = session.compaction.summary
            if (summary.isBlank()) return@forEach
            written += runCatching {
                indexCompactionNow(
                    sessionId = session.id,
                    projectId = projectId,
                    summary = summary,
                    archivePath = session.compaction.archives.lastOrNull(),
                    sourceUpdatedAt = session.updatedAt,
                )
            }.onFailure { Log.w(TAG, "rebuild ${session.id} failed", it) }.getOrDefault(0)
        }
        return written
    }

    fun flush() {
        runCatching { io.submit {}.get() }
    }

    // ------------------------------------------------------------------ //

    @Synchronized
    private fun upsert(draft: MemoryDraft): MemoryWriteResult {
        if (draft.text.isBlank()) return MemoryWriteResult(ACTION_SKIPPED, -1)
        if (MemoryRedactor.isSensitive(draft.text) ||
            (draft.sourcePath.isNotBlank() && MemoryRedactor.isSensitive(draft.sourcePath))
        ) {
            return MemoryWriteResult(ACTION_SENSITIVE, -1)
        }
        if (!store.available) return MemoryWriteResult(ACTION_UNAVAILABLE, -1)

        val hash = MemoryStore.hashOf(draft.text)
        val result = store.transaction {
            // 同作用域的完全相同文本：活着就是重复；死了先清掉，允许重新入库。
            val existing = store.findByHash(hash, draft.scope, draft.projectId)
            if (existing != null) {
                if (existing.isAlive()) return@transaction MemoryWriteResult(ACTION_DUPLICATE, existing.id)
                store.purge(existing.id)
            }

            val embedding = runCatching { LocalEmbedding.embed(draft.text) }.getOrNull()
            val sameSubject = if (draft.subject.isNotBlank()) {
                store.findLiveBySubject(draft.subject, draft.type, draft.projectId, draft.scope)
                    .filter { it.text != draft.text }
            } else {
                emptyList()
            }

            // 1. 真正冲突（版本/路径/端口/错误码变化）才替代。
            val conflicts = sameSubject.filter { MemoryExtractor.conflicts(it.text, draft.text) }
            if (conflicts.isNotEmpty()) {
                val id = store.insert(draft, embedding)
                    ?: return@transaction MemoryWriteResult(ACTION_ERROR, -1)
                conflicts.forEach { store.markSuperseded(it.id, id) }
                return@transaction MemoryWriteResult(ACTION_SUPERSEDED, id)
            }

            // 2. 近重复：用新文本覆盖原条目，避免“合并”把新值丢掉。
            if (embedding != null) {
                val near = store.liveForScope(draft.scope, draft.projectId)
                    .firstOrNull { candidate ->
                        candidate.type == draft.type && candidate.text != draft.text &&
                            LocalEmbedding.cosine(embedding, candidate.embedding) >= NEAR_DUPLICATE
                    }
                if (near != null && store.updateText(
                        id = near.id,
                        text = draft.text,
                        subject = draft.subject.ifBlank { near.subject },
                        expiresAt = expiryFor(draft.type),
                        embedding = embedding,
                    )
                ) {
                    store.mergeInto(near.id, hash)
                    return@transaction MemoryWriteResult(ACTION_MERGED, near.id)
                }
            }

            // 3. 同 subject 但不冲突：并存，旧条目降权，避免压过新结论。
            val id = store.insert(draft, embedding)
                ?: return@transaction MemoryWriteResult(ACTION_ERROR, -1)
            sameSubject.forEach { store.adjustImportance(it.id, -0.2f) }
            MemoryWriteResult(ACTION_NEW, id)
        }

        val outcome = result ?: return MemoryWriteResult(ACTION_ERROR, -1)
        store.enforceSizeLimit()
        return outcome
    }

    private fun expiryFor(type: MemoryType): Long {
        val ttl = MemoryType.defaultTtl(type)
        return if (ttl > 0) System.currentTimeMillis() + ttl else 0L
    }

    private companion object {
        const val TAG = "WBHub"
        const val NEAR_DUPLICATE = 0.95
        const val MAX_TEXT = 400
    }
}

/** 一次写入的结果；[action] 取 [ACTION_NEW] 等常量。 */
data class MemoryWriteResult(val action: String, val id: Long)

/** 更新记忆的结果；[reason] 用于给模型一个可读的失败原因。 */
data class MemoryUpdateResult(val ok: Boolean, val reason: String)

const val ACTION_NEW = "new"
const val ACTION_MERGED = "merged"
const val ACTION_DUPLICATE = "duplicate"
const val ACTION_SUPERSEDED = "superseded"
const val ACTION_SKIPPED = "skipped"
const val ACTION_SENSITIVE = "sensitive"
const val ACTION_UNAVAILABLE = "unavailable"
const val ACTION_ERROR = "error"
const val ACTION_TOO_LONG = "too_long"

/**
 * 与 [MemoryIndexer.update] 同一套校验，但用于设置页的人工编辑。
 *
 * 人工编辑只改这一条：不触发 subject 冲突替代，也不延长有效期；自动索引仍走
 * [MemoryIndexer.update]，那里保留“新结论替代旧结论”的逻辑。
 */
fun updateMemory(store: MemoryStore, id: Long, text: String): MemoryUpdateResult {
    val record = store.get(id)
        ?: return MemoryUpdateResult(false, "not_found")
    if (!record.isAlive()) return MemoryUpdateResult(false, "not_found")
    val updated = text.trim()
    if (updated.isEmpty()) return MemoryUpdateResult(false, "empty")
    if (updated.length > 400) return MemoryUpdateResult(false, "too_long")
    if (MemoryRedactor.isSensitive(updated)) return MemoryUpdateResult(false, "sensitive")

    val hash = MemoryStore.hashOf(updated)
    val duplicate = store.findByHash(hash, record.scope, record.projectId)
    if (duplicate != null && duplicate.id != id && duplicate.isAlive()) {
        return MemoryUpdateResult(false, "duplicate")
    }

    val subject = MemoryExtractor.subjectOf(updated)
    val embedding = runCatching { LocalEmbedding.embed(updated) }.getOrNull()
    val changed = store.transaction {
        store.updateText(
            id = id,
            text = updated,
            subject = subject,
            expiresAt = record.expiresAt,
            embedding = embedding,
        )
    } ?: false
    return MemoryUpdateResult(changed, if (changed) "ok" else "error")
}
