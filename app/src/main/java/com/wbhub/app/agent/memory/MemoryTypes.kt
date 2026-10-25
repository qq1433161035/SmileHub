package com.wbhub.app.agent.memory

/**
 * 长期记忆的类型。
 *
 * 分类决定了默认有效期、作用域和检索时的权重：画像与技能长期有效，
 * 情景记忆会过期，项目事实介于两者之间。
 */
enum class MemoryType {
    PROFILE,
    SEMANTIC,
    EPISODIC,
    PROCEDURAL,
    ;

    val dbName: String get() = name.lowercase()

    companion object {
        fun from(value: String?): MemoryType =
            entries.firstOrNull { it.dbName == value?.trim()?.lowercase() } ?: SEMANTIC

        /** 默认有效期（毫秒）；0 表示永不过期。 */
        fun defaultTtl(type: MemoryType): Long = when (type) {
            PROFILE -> 0L
            SEMANTIC -> 365L * 24 * 3600 * 1000
            EPISODIC -> 180L * 24 * 3600 * 1000
            PROCEDURAL -> 0L
        }
    }
}

/** 记忆的可见范围。 */
enum class MemoryScope {
    GLOBAL,
    PROJECT,
    SESSION,
    ;

    val dbName: String get() = name.lowercase()

    companion object {
        fun from(value: String?): MemoryScope =
            entries.firstOrNull { it.dbName == value?.trim()?.lowercase() } ?: PROJECT
    }
}

/** 一条记忆的完整记录。 */
data class MemoryRecord(
    val id: Long,
    val type: MemoryType,
    val scope: MemoryScope,
    val projectId: String,
    val sessionId: String,
    val subject: String,
    val text: String,
    val hash: String,
    val sourceKind: String,
    val sourcePath: String,
    val tags: String,
    val importance: Float,
    val embedding: FloatArray?,
    val embeddingModel: String,
    val createdAt: Long,
    val updatedAt: Long,
    val expiresAt: Long,
    val supersededBy: Long,
    val mergedFrom: String,
    val lastUsedAt: Long,
    val useCount: Int,
)

/** 写入前的一条草稿，还没有 id。 */
data class MemoryDraft(
    val type: MemoryType,
    val scope: MemoryScope,
    val projectId: String,
    val sessionId: String,
    val subject: String,
    val text: String,
    val sourceKind: String,
    val sourcePath: String,
    val tags: String,
    val importance: Float,
    /** 回填时用会话的真实时间；0 表示写入时刻。 */
    val sourceUpdatedAt: Long = 0L,
)

/** 一条审计事件，查看器用它展示时间线。 */
data class MemoryEvent(
    val action: String,
    val actor: String,
    val detail: String,
    val createdAt: Long,
)

/** 检索命中。 */
data class MemoryHit(val record: MemoryRecord, val score: Double)

/** 一条记忆当前是否可被召回。 */
fun MemoryRecord.isAlive(now: Long = System.currentTimeMillis()): Boolean =
    supersededBy == 0L && (expiresAt == 0L || expiresAt > now)

/** 一次召回的结果；[preamble] 为 null 表示没有可注入的内容。 */
data class RecallResult(val preamble: String?, val count: Int, val tokens: Int)

/** 记忆库统计，用于设置页展示。 */
data class MemoryStats(
    val total: Int = 0,
    val expired: Int = 0,
    val superseded: Int = 0,
    val bytes: Long = 0,
)
