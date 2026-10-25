package com.wbhub.app.agent.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.wbhub.app.storage.AppStorage
import java.io.File
import java.security.MessageDigest

/**
 * 记忆库的 SQLite 存储。
 *
 * 只由主进程访问：Agent 与工具都在主进程，跨进程只会多一份连接和锁竞争。
 * 所有写操作都包在事务里，召回只做读，不阻塞写。
 */
class MemoryStore private constructor(context: Context) {

    private val file = File(AppStorage.memory(context), "memory.db")
    private val db: SQLiteDatabase? = runCatching {
        openDatabase(file)
    }.getOrElse { error ->
        // 损坏时保留现场再重建；-wal/-shm 必须一起挪走，否则旧日志会污染新库。
        Log.w(TAG, "memory db corrupt, rebuilding", error)
        val stamp = System.currentTimeMillis()
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            runCatching {
                File(file.path + suffix).renameTo(File(file.path + suffix + ".corrupt-$stamp"))
            }
        }
        runCatching { openDatabase(file) }.getOrNull()
    }

    val available: Boolean get() = db != null

    // ------------------------------------------------------------------ //
    // Writes
    // ------------------------------------------------------------------ //

    fun insert(draft: MemoryDraft, embedding: FloatArray?): Long? {
        val database = db ?: return null
        val now = System.currentTimeMillis()
        val created = if (draft.sourceUpdatedAt > 0) draft.sourceUpdatedAt else now
        val ttl = MemoryType.defaultTtl(draft.type)
        val values = ContentValues().apply {
            put("memory_type", draft.type.dbName)
            put("scope", draft.scope.dbName)
            put("project_id", draft.projectId)
            put("session_id", draft.sessionId)
            put("subject", draft.subject)
            put("text", draft.text)
            put("hash", hashOf(draft.text))
            put("source_kind", draft.sourceKind)
            put("source_path", draft.sourcePath)
            put("tags", draft.tags)
            put("importance", draft.importance)
            put("tokens", 0)
            if (embedding != null) {
                put("embedding", LocalEmbedding.serialize(embedding))
                put("embedding_model", LocalEmbedding.MODEL)
                put("embedding_dim", embedding.size)
            }
            put("created_at", created)
            put("updated_at", created)
            // TTL 从“这次入库”起算：回填旧会话时也能拿到完整的有效期。
            put("expires_at", if (ttl > 0) now + ttl else 0L)
            put("superseded_by", 0L)
            put("merged_from", "")
            put("last_used_at", 0L)
            put("use_count", 0)
        }
        val id = runCatching {
            database.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        }.onFailure { Log.w(TAG, "memory insert failed", it) }.getOrDefault(-1L)
        if (id <= 0) return null
        event(database, id, "create", "", values.getAsString("hash"), "auto", "")
        prune(database, id)
        return id
    }

    /** 按作用域取精确匹配；同一文本可以同时存在于不同作用域。 */
    fun findByHash(hash: String, scope: MemoryScope, projectId: String): MemoryRecord? = queryOne(
        "$COL_HASH = ? AND $COL_SCOPE = ? AND $COL_PROJECT = ?",
        arrayOf(hash, scope.dbName, projectId),
    )

    fun findLiveBySubject(
        subject: String,
        type: MemoryType,
        projectId: String,
        scope: MemoryScope,
    ): List<MemoryRecord> {
        if (subject.isBlank()) return emptyList()
        val database = db ?: return emptyList()
        val now = System.currentTimeMillis()
        return query(
            database,
            "$COL_SUBJECT = ? AND $COL_TYPE = ? AND $COL_PROJECT = ? AND $COL_SCOPE = ? " +
                "AND $COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?)",
            arrayOf(subject, type.dbName, projectId, scope.dbName, now.toString()),
            "updated_at DESC",
            10,
        )
    }

    /** 同作用域下最近的存活条目，用于近重复合并。 */
    fun liveForScope(scope: MemoryScope, projectId: String, limit: Int = 300): List<MemoryRecord> {
        val database = db ?: return emptyList()
        val now = System.currentTimeMillis()
        return query(
            database,
            "$COL_SCOPE = ? AND $COL_PROJECT = ? AND $COL_SUPERSEDED = 0 " +
                "AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?)",
            arrayOf(scope.dbName, projectId, now.toString()),
            "updated_at DESC",
            limit,
        )
    }

    /**
     * 召回候选：全局、当前工程，以及“别的会话”的 session 条目；
     * 当前会话的条目已经在上下文里，不再重复注入。
     */
    fun forRecall(
        projectId: String,
        sessionId: String,
        limit: Int = 500,
        offset: Int = 0,
    ): List<MemoryRecord> {
        val database = db ?: return emptyList()
        val now = System.currentTimeMillis()
        return query(
            database,
            "$COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?) AND (" +
                "$COL_SCOPE = 'global' OR ($COL_SCOPE = 'project' AND $COL_PROJECT = ?) OR " +
                "($COL_SCOPE = 'session' AND $COL_SESSION != ?))",
            arrayOf(now.toString(), projectId, sessionId),
            "updated_at DESC",
            limit,
            offset,
        )
    }

    fun markSuperseded(id: Long, newId: Long) {
        val database = db ?: return
        val before = queryOne("$COL_ID = ?", arrayOf(id.toString()))?.hash.orEmpty()
        val values = ContentValues().apply { put("superseded_by", newId) }
        val changed = runCatching {
            database.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        }.getOrDefault(0)
        if (changed > 0) {
            event(database, id, "supersede", before, "", "auto", "superseded_by=$newId")
        }
    }

    fun mergeInto(id: Long, mergedHash: String) {
        val database = db ?: return
        runCatching {
            database.execSQL(
                "UPDATE $TABLE SET merged_from = CASE " +
                    "WHEN merged_from = '' THEN ? " +
                    "WHEN instr(merged_from, ?) > 0 THEN merged_from " +
                    "ELSE merged_from || ',' || ? END " +
                    "WHERE $COL_ID = ?",
                arrayOf(mergedHash, mergedHash, mergedHash, id),
            )
        }
        event(database, id, "merge", "", mergedHash, "auto", "")
    }

    /** 同 subject 但不冲突时保留两条，只把旧条目降权。 */
    fun adjustImportance(id: Long, delta: Float) {
        val database = db ?: return
        runCatching {
            database.execSQL(
                "UPDATE $TABLE SET importance = MAX(0.0, MIN(1.0, importance + ?)) WHERE $COL_ID = ?",
                arrayOf(delta, id),
            )
        }
    }

    fun updateText(
        id: Long,
        text: String,
        subject: String,
        expiresAt: Long,
        embedding: FloatArray?,
    ): Boolean {
        val database = db ?: return false
        val values = ContentValues().apply {
            put("text", text)
            put("hash", hashOf(text))
            put("subject", subject)
            put("expires_at", expiresAt)
            put("updated_at", System.currentTimeMillis())
            if (embedding != null) {
                put("embedding", LocalEmbedding.serialize(embedding))
                put("embedding_model", LocalEmbedding.MODEL)
                put("embedding_dim", embedding.size)
            }
        }
        val changed = runCatching {
            database.update(TABLE, values, "$COL_ID = ?", arrayOf(id.toString()))
        }.onFailure { Log.w(TAG, "memory update failed", it) }.getOrDefault(0)
        if (changed > 0) event(database, id, "update", "", values.getAsString("hash"), "user", "")
        return changed > 0
    }

    fun touch(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = db ?: return
        val now = System.currentTimeMillis()
        runCatching {
            database.beginTransaction()
            try {
                ids.forEach { id ->
                    database.execSQL(
                        "UPDATE $TABLE SET last_used_at = ?, use_count = use_count + 1 WHERE $COL_ID = ?",
                        arrayOf(now, id),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    /** 把多条写操作放进同一事务；失败整体回滚。 */
    fun <T> transaction(block: () -> T): T? {
        val database = db ?: return null
        return runCatching {
            database.beginTransaction()
            try {
                val value = block()
                database.setTransactionSuccessful()
                value
            } finally {
                database.endTransaction()
            }
        }.onFailure { Log.w(TAG, "memory transaction failed", it) }.getOrNull()
    }

    /**
     * 删除已被替代或过期的死行，给同文本重新入库让路。
     * 不删除存活条目。
     */
    fun purge(id: Long) {
        val database = db ?: return
        val record = queryOne("$COL_ID = ?", arrayOf(id.toString())) ?: return
        // 被它替代的旧条目要恢复，否则会指向一个不存在的 id。
        val children = query(database, "$COL_SUPERSEDED = ?", arrayOf(id.toString()), "updated_at DESC", 50)
        val now = System.currentTimeMillis()
        children.forEach { child ->
            val ttl = MemoryType.defaultTtl(child.type)
            val values = ContentValues().apply {
                put("superseded_by", 0L)
                put("expires_at", if (ttl > 0) now + ttl else 0L)
                put("updated_at", now)
            }
            runCatching { database.update(TABLE, values, "$COL_ID = ?", arrayOf(child.id.toString())) }
        }
        val removed = runCatching { database.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString())) }
            .getOrDefault(0)
        if (removed > 0) event(database, id, "expire", record.hash, "", "auto", "purged")
    }

    fun delete(id: Long): Boolean {
        val database = db ?: return false
        var removed = false
        transaction {
            val record = queryOne("$COL_ID = ?", arrayOf(id.toString()))
            // 删除新结论时恢复被它替代的旧结论，避免 subject 永久空掉。
            val children = query(
                database,
                "$COL_SUPERSEDED = ?",
                arrayOf(id.toString()),
                "updated_at DESC",
                50,
            )
            val now = System.currentTimeMillis()
            children.forEach { child ->
                val ttl = MemoryType.defaultTtl(child.type)
                val values = ContentValues().apply {
                    put("superseded_by", 0L)
                    put("expires_at", if (ttl > 0) now + ttl else 0L)
                    put("updated_at", now)
                }
                val changed = runCatching {
                    database.update(TABLE, values, "$COL_ID = ?", arrayOf(child.id.toString()))
                }.getOrDefault(0)
                if (changed > 0) {
                    event(database, child.id, "update", child.hash, child.hash, "auto", "restored")
                }
            }
            removed = runCatching {
                database.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
            }.getOrDefault(0) > 0
            if (removed) event(database, id, "delete", record?.hash.orEmpty(), "", "user", "")
            removed
        }
        return removed
    }

    /** 删除会话时清掉它自己的 session 作用域条目。 */
    fun deleteBySession(sessionId: String) {
        if (sessionId.isBlank()) return
        val database = db ?: return
        transaction {
            while (true) {
                val rows = query(
                    database,
                    "$COL_SESSION = ? AND $COL_SCOPE = 'session'",
                    arrayOf(sessionId),
                    "updated_at DESC",
                    500,
                )
                if (rows.isEmpty()) break
                rows.forEach { record ->
                    runCatching { database.delete(TABLE, "$COL_ID = ?", arrayOf(record.id.toString())) }
                    event(database, record.id, "delete", record.hash, "", "auto", "session deleted")
                }
            }
        }
    }

    /** 物理清理过期超过宽限期的条目；返回删除条数。 */
    fun purgeExpired(graceMs: Long = 30L * 24 * 3600 * 1000): Int {
        val database = db ?: return 0
        val cutoff = System.currentTimeMillis() - graceMs
        var removed = 0
        transaction {
            while (true) {
                val rows = query(
                    database,
                    "$COL_EXPIRES > 0 AND $COL_EXPIRES <= ?",
                    arrayOf(cutoff.toString()),
                    "expires_at ASC",
                    200,
                )
                if (rows.isEmpty()) break
                rows.forEach { record ->
                    val count = runCatching {
                        database.delete(TABLE, "$COL_ID = ?", arrayOf(record.id.toString()))
                    }.getOrDefault(0)
                    if (count > 0) {
                        removed += count
                        event(database, record.id, "expire", record.hash, "", "auto", "ttl")
                    }
                }
                if (rows.size < 200) break
            }
            removed
        }
        return removed
    }

    fun clear() {
        val database = db ?: return
        runCatching {
            database.delete(TABLE, null, null)
            database.delete(TABLE_EVENT, null, null)
        }
        // 让“占用空间”真实下降；VACUUM 不能在事务里执行。
        runCatching { database.execSQL("VACUUM") }
    }

    /** 一条记忆的审计时间线，最新在前。 */
    fun events(itemId: Long, limit: Int = 10): List<MemoryEvent> {
        val database = db ?: return emptyList()
        return runCatching {
            database.query(
                TABLE_EVENT,
                arrayOf("action", "actor", "detail", "created_at"),
                "item_id = ?",
                arrayOf(itemId.toString()),
                null,
                null,
                "created_at DESC",
                limit.toString(),
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            MemoryEvent(
                                action = cursor.getString(0),
                                actor = cursor.getString(1),
                                detail = cursor.getString(2),
                                createdAt = cursor.getLong(3),
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------ //
    // Reads
    // ------------------------------------------------------------------ //

    fun list(
        type: MemoryType? = null,
        scope: MemoryScope? = null,
        limit: Int = 200,
        includeDead: Boolean = false,
    ): List<MemoryRecord> {
        val database = db ?: return emptyList()
        val where = StringBuilder()
        val args = mutableListOf<String>()
        if (!includeDead) {
            where.append("$COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?)")
            args += System.currentTimeMillis().toString()
        }
        if (type != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("$COL_TYPE = ?")
            args += type.dbName
        }
        if (scope != null) {
            if (where.isNotEmpty()) where.append(" AND ")
            where.append("$COL_SCOPE = ?")
            args += scope.dbName
        }
        return query(
            database,
            where.toString().ifEmpty { null },
            args.toTypedArray(),
            "updated_at DESC",
            limit,
        )
    }

    fun get(id: Long): MemoryRecord? = queryOne("$COL_ID = ?", arrayOf(id.toString()))

    fun stats(): MemoryStats {
        val database = db ?: return MemoryStats()
        val now = System.currentTimeMillis()
        // “已保存”只算当前可召回的条目：被替代/过期的不再计入。
        val total = count(
            database,
            "$COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?)",
            arrayOf(now.toString()),
        )
        val expired = count(
            database,
            "$COL_EXPIRES > 0 AND $COL_EXPIRES <= ? AND $COL_SUPERSEDED = 0",
            arrayOf(now.toString()),
        )
        val superseded = count(database, "$COL_SUPERSEDED > 0", null)
        val bytes = listOf(file, File(file.path + "-wal"), File(file.path + "-shm"))
            .sumOf { if (it.exists()) it.length() else 0L }
        return MemoryStats(total, expired, superseded, bytes)
    }

    private fun count(database: SQLiteDatabase, where: String?, args: Array<String>?): Int =
        runCatching {
            database.rawQuery("SELECT COUNT(*) FROM $TABLE" + (where?.let { " WHERE $it" } ?: ""), args)
                .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }.getOrDefault(0)

    private fun queryOne(where: String, args: Array<String>): MemoryRecord? {
        val database = db ?: return null
        return query(database, where, args, "updated_at DESC", 1).firstOrNull()
    }

    private fun query(
        database: SQLiteDatabase,
        where: String?,
        args: Array<String>?,
        order: String,
        limit: Int,
        offset: Int = 0,
    ): List<MemoryRecord> {
        val sql = buildString {
            append("SELECT * FROM ").append(TABLE)
            if (!where.isNullOrBlank()) append(" WHERE ").append(where)
            append(" ORDER BY ").append(order)
            append(" LIMIT ").append(limit)
            if (offset > 0) append(" OFFSET ").append(offset)
        }
        val cursor = runCatching { database.rawQuery(sql, args) }.getOrNull() ?: return emptyList()
        return cursor.use {
            buildList {
                while (it.moveToNext()) add(read(it))
            }
        }
    }

    private fun read(cursor: Cursor): MemoryRecord {
        val model = cursor.getString(cursor.getColumnIndexOrThrow(COL_EMBEDDING_MODEL))
        val vector = LocalEmbedding.deserialize(
            cursor.getBlob(cursor.getColumnIndexOrThrow(COL_EMBEDDING)),
        )
        // 模型或维度变了就当作没有向量：旧向量不能参与余弦，但仍可按词法召回。
        val embedding = vector?.takeIf {
            model == LocalEmbedding.MODEL && it.size == LocalEmbedding.DIM
        }
        return MemoryRecord(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            type = MemoryType.from(cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE))),
            scope = MemoryScope.from(cursor.getString(cursor.getColumnIndexOrThrow(COL_SCOPE))),
            projectId = cursor.getString(cursor.getColumnIndexOrThrow(COL_PROJECT)),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION)),
            subject = cursor.getString(cursor.getColumnIndexOrThrow(COL_SUBJECT)),
            text = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)),
            hash = cursor.getString(cursor.getColumnIndexOrThrow(COL_HASH)),
            sourceKind = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_KIND)),
            sourcePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE_PATH)),
            tags = cursor.getString(cursor.getColumnIndexOrThrow(COL_TAGS)),
            importance = cursor.getFloat(cursor.getColumnIndexOrThrow(COL_IMPORTANCE)),
            embedding = embedding,
            embeddingModel = model,
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED)),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_UPDATED)),
            expiresAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_EXPIRES)),
            supersededBy = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SUPERSEDED)),
            mergedFrom = cursor.getString(cursor.getColumnIndexOrThrow(COL_MERGED_FROM)),
            lastUsedAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_USED)),
            useCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_USE_COUNT)),
        )
    }

    fun event(itemId: Long, action: String, before: String, after: String, actor: String, detail: String) {
        val database = db ?: return
        event(database, itemId, action, before, after, actor, detail)
    }

    private fun event(
        database: SQLiteDatabase,
        itemId: Long,
        action: String,
        before: String,
        after: String,
        actor: String,
        detail: String,
    ) {
        val values = ContentValues().apply {
            put("item_id", itemId)
            put("action", action)
            put("before_hash", before)
            put("after_hash", after)
            put("actor", actor)
            put("detail", detail)
            put("created_at", System.currentTimeMillis())
        }
        runCatching { database.insert(TABLE_EVENT, null, values) }
    }

    /**
     * 只统计存活条目，并排除刚插入的行；删除前写审计。
     */
    private fun prune(database: SQLiteDatabase, keepId: Long) {
        val now = System.currentTimeMillis()
        val live = count(
            database,
            "$COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?)",
            arrayOf(now.toString()),
        )
        if (live <= MAX_ITEMS) return
        val over = (live - MAX_ITEMS).coerceAtMost(500)
        val victims = query(
            database,
            "$COL_SUPERSEDED = 0 AND ($COL_EXPIRES = 0 OR $COL_EXPIRES > ?) AND $COL_ID != ?",
            arrayOf(now.toString(), keepId.toString()),
            "importance ASC, (CASE WHEN last_used_at = 0 THEN created_at ELSE last_used_at END) ASC",
            over,
        )
        val deleted = mutableListOf<Long>()
        victims.forEach { record ->
            val removed = runCatching {
                database.delete(TABLE, "$COL_ID = ?", arrayOf(record.id.toString()))
            }.getOrDefault(0)
            if (removed > 0) {
                deleted += record.id
                event(database, record.id, "expire", record.hash, "", "auto", "capacity")
            }
        }
        restoreChildrenOf(database, deleted)
    }

    /** 数据库超过上限时按“低重要度 + 最久未用”删一批，并尝试回收文件空间。 */
    fun enforceSizeLimit() {
        val database = db ?: return
        val bytes = listOf(file, File(file.path + "-wal")).sumOf { if (it.exists()) it.length() else 0L }
        if (bytes <= MAX_DB_BYTES) return
        transaction {
            val victims = query(
                database,
                null,
                null,
                "importance ASC, (CASE WHEN last_used_at = 0 THEN created_at ELSE last_used_at END) ASC",
                500,
            )
            val deleted = mutableListOf<Long>()
            victims.forEach { record ->
                val removed = runCatching {
                    database.delete(TABLE, "$COL_ID = ?", arrayOf(record.id.toString()))
                }.getOrDefault(0)
                if (removed > 0) {
                    deleted += record.id
                    event(database, record.id, "expire", record.hash, "", "auto", "size")
                }
            }
            restoreChildrenOf(database, deleted)
        }
        runCatching { database.execSQL("VACUUM") }
    }

    /** 删除被引用行后，把指向它们的 superseded_by 复位，避免悬空。 */
    private fun restoreChildrenOf(database: SQLiteDatabase, ids: List<Long>) {
        if (ids.isEmpty()) return
        val placeholders = ids.joinToString(",") { "?" }
        val args = ArrayList<String>(ids.size + 1)
        args += System.currentTimeMillis().toString()
        ids.forEach { args += it.toString() }
        runCatching {
            database.execSQL(
                "UPDATE $TABLE SET $COL_SUPERSEDED = 0, updated_at = ? " +
                    "WHERE $COL_SUPERSEDED IN ($placeholders)",
                args.toTypedArray(),
            )
        }
    }

    private fun openDatabase(target: File): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(target, null).apply {
            rawQuery("PRAGMA journal_mode=WAL", null).use { it.moveToFirst() }
            // busy_timeout 也会返回一行；某些 Android 版本对 execSQL 执行 PRAGMA 会直接抛异常。
            rawQuery("PRAGMA busy_timeout=5000", null).use { it.moveToFirst() }
            rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (cursor.moveToFirst() && cursor.getString(0) != "ok") {
                    throw IllegalStateException("memory db integrity check failed")
                }
            }
            createSchema(this)
        }

    companion object {
        private const val TAG = "WBHub"
        private const val TABLE = "memory_item"
        private const val TABLE_EVENT = "memory_event"
        private const val MAX_ITEMS = 20_000
        private const val MAX_DB_BYTES = 64L * 1024 * 1024

        private const val COL_ID = "id"
        private const val COL_TYPE = "memory_type"
        private const val COL_SCOPE = "scope"
        private const val COL_PROJECT = "project_id"
        private const val COL_SESSION = "session_id"
        private const val COL_SUBJECT = "subject"
        private const val COL_TEXT = "text"
        private const val COL_HASH = "hash"
        private const val COL_SOURCE_KIND = "source_kind"
        private const val COL_SOURCE_PATH = "source_path"
        private const val COL_TAGS = "tags"
        private const val COL_IMPORTANCE = "importance"
        private const val COL_EMBEDDING = "embedding"
        private const val COL_EMBEDDING_MODEL = "embedding_model"
        private const val COL_CREATED = "created_at"
        private const val COL_UPDATED = "updated_at"
        private const val COL_EXPIRES = "expires_at"
        private const val COL_SUPERSEDED = "superseded_by"
        private const val COL_MERGED_FROM = "merged_from"
        private const val COL_LAST_USED = "last_used_at"
        private const val COL_USE_COUNT = "use_count"

        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore {
            val current = instance
            if (current != null && current.available) return current
            return synchronized(this) {
                val existing = instance
                if (existing != null && existing.available) {
                    existing
                } else {
                    // 打不开的实例不缓存，下次调用允许重试。
                    MemoryStore(context.applicationContext).also {
                        instance = it.takeIf { store -> store.available }
                    }
                }
            }
        }

        /** 标准化的文本哈希，用于精确去重；与向量化共用同一套归一化。 */
        fun hashOf(text: String): String {
            val normalized = LocalEmbedding.normalize(text)
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { byte -> "%02x".format(byte) }
        }

        private fun createSchema(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE (
                  $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                  $COL_TYPE TEXT NOT NULL,
                  $COL_SCOPE TEXT NOT NULL,
                  $COL_PROJECT TEXT NOT NULL DEFAULT '',
                  $COL_SESSION TEXT NOT NULL DEFAULT '',
                  $COL_SUBJECT TEXT NOT NULL DEFAULT '',
                  $COL_TEXT TEXT NOT NULL,
                  $COL_HASH TEXT NOT NULL,
                  $COL_SOURCE_KIND TEXT NOT NULL DEFAULT '',
                  $COL_SOURCE_PATH TEXT NOT NULL DEFAULT '',
                  $COL_TAGS TEXT NOT NULL DEFAULT '',
                  $COL_IMPORTANCE REAL NOT NULL DEFAULT 0.5,
                  tokens INTEGER NOT NULL DEFAULT 0,
                  $COL_EMBEDDING BLOB,
                  $COL_EMBEDDING_MODEL TEXT NOT NULL DEFAULT '',
                  embedding_dim INTEGER NOT NULL DEFAULT 0,
                  $COL_CREATED INTEGER NOT NULL,
                  $COL_UPDATED INTEGER NOT NULL,
                  $COL_EXPIRES INTEGER NOT NULL DEFAULT 0,
                  $COL_SUPERSEDED INTEGER NOT NULL DEFAULT 0,
                  $COL_MERGED_FROM TEXT NOT NULL DEFAULT '',
                  $COL_LAST_USED INTEGER NOT NULL DEFAULT 0,
                  $COL_USE_COUNT INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EVENT (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  item_id INTEGER NOT NULL,
                  action TEXT NOT NULL,
                  before_hash TEXT NOT NULL DEFAULT '',
                  after_hash TEXT NOT NULL DEFAULT '',
                  actor TEXT NOT NULL DEFAULT '',
                  detail TEXT NOT NULL DEFAULT '',
                  created_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            database.execSQL(
                "CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)",
            )
            database.execSQL(
                "INSERT OR REPLACE INTO meta(key, value) VALUES('schema_version', '2')",
            )
            // 唯一性按作用域隔离：同一句话可以同时是 global 与 project 的记忆。
            // 旧库的索引只含 hash，启动时重建一次即可完成迁移。
            database.execSQL("DROP INDEX IF EXISTS idx_memory_hash")
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_memory_hash ON $TABLE(" +
                    "$COL_SCOPE, $COL_PROJECT, $COL_HASH)",
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_scope ON $TABLE($COL_SCOPE, $COL_PROJECT)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_live ON $TABLE($COL_SUPERSEDED, $COL_EXPIRES)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_subject ON $TABLE($COL_SUBJECT)")
            database.execSQL("CREATE INDEX IF NOT EXISTS idx_event_item ON $TABLE_EVENT(item_id, created_at DESC)")
        }
    }
}
