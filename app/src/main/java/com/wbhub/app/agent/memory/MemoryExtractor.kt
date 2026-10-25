package com.wbhub.app.agent.memory

/**
 * 把压缩摘要拆成可入库的记忆草稿。
 *
 * 摘要里的“已完成/目标”是情景记忆，“关键产物/关键事实”是项目事实。
 * 不做模型调用：摘要本身已经是模型产出的结构化文本。
 */
object MemoryExtractor {

    private val packagePattern = Regex("\\b(?:com|org|net|io|cn)\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+\\b")
    private val pathPattern = Regex("(?:/[A-Za-z0-9._-]+){2,}")
    private val versionPattern = Regex("\\b\\d+(?:\\.\\d+){1,3}\\b")
    private val portPattern = Regex("(?i)\\bport\\s*[:=]?\\s*(\\d{2,5})\\b")
    private val errorPattern = Regex("\\b(?:E_[A-Z_]+|HTTP\\s*\\d{3})\\b")
    private val numberedPrefix = Regex("^\\d+[.、)]\\s+")
    private const val MAX_ITEMS_PER_SECTION = 3
    private const val MAX_TEXT = 400

    private data class Section(val name: String, val items: MutableList<String> = mutableListOf())

    fun fromSummary(
        summary: String,
        sessionId: String,
        projectId: String,
        archivePath: String,
        sourceUpdatedAt: Long = 0L,
    ): List<MemoryDraft> {
        if (summary.isBlank()) return emptyList()
        val sections = parse(summary)
        val drafts = mutableListOf<MemoryDraft>()

        fun add(section: String, type: MemoryType, scope: MemoryScope, importance: Float, limit: Int) {
            val items = sections
                .filter { section in sectionNames(it.name) }
                .flatMap { it.items }
            items.asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(limit)
                .forEach { text ->
                    drafts += draftOf(
                        text, type, scope, projectId, sessionId, archivePath, importance, sourceUpdatedAt,
                    )
                }
        }

        add("关键产物", MemoryType.SEMANTIC, MemoryScope.PROJECT, 0.8f, MAX_ITEMS_PER_SECTION)
        add("关键事实", MemoryType.SEMANTIC, MemoryScope.PROJECT, 0.8f, MAX_ITEMS_PER_SECTION)
        add("约束", MemoryType.SEMANTIC, MemoryScope.PROJECT, 0.8f, MAX_ITEMS_PER_SECTION)
        add("已完成", MemoryType.EPISODIC, MemoryScope.PROJECT, 0.6f, 2)
        add("未完成", MemoryType.EPISODIC, MemoryScope.PROJECT, 0.7f, 2)
        add("下一步", MemoryType.EPISODIC, MemoryScope.PROJECT, 0.7f, 2)
        add("目标", MemoryType.EPISODIC, MemoryScope.PROJECT, 0.9f, 1)

        // 摘要结构变了（例如模型没按模板输出）时，至少保留一条短摘要，
        // 否则这次压缩对长期记忆完全没有贡献。
        if (drafts.isEmpty()) {
            val fallback = summary.trim().take(MAX_TEXT)
            if (fallback.isNotEmpty()) {
                drafts += draftOf(
                    fallback,
                    MemoryType.EPISODIC,
                    MemoryScope.PROJECT,
                    projectId,
                    sessionId,
                    archivePath,
                    0.5f,
                    sourceUpdatedAt,
                )
            }
        }
        return drafts.distinctBy { MemoryStore.hashOf(it.text) }
    }

    private fun parse(summary: String): List<Section> {
        val sections = mutableListOf<Section>()
        var current: Section? = null
        for (raw in summary.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("【")) continue
            headingOf(line)?.let { heading ->
                current = Section(heading)
                sections += current
                return@let
            } ?: run {
                val text = bulletOf(line) ?: return@run
                val target = current ?: Section("其他").also { sections += it; current = it }
                target.items += text
            }
        }
        return sections
    }

    private fun headingOf(line: String): String? {
        if (line.startsWith("#")) {
            return line.trimStart('#').trim().removeSuffix("：").removeSuffix(":")
                .takeIf { it.isNotEmpty() && it.length <= 20 }
        }
        if (line.startsWith("-") || line.startsWith("*") || line.startsWith("+")) return null
        if (!line.endsWith("：") && !line.endsWith(":")) return null
        return line.dropLast(1).trim().takeIf { it.isNotEmpty() && it.length <= 20 }
    }

    private fun bulletOf(line: String): String? {
        val text = when {
            line.startsWith("-") || line.startsWith("*") || line.startsWith("+") -> line.drop(1)
            numberedPrefix.containsMatchIn(line) -> line.replaceFirst(numberedPrefix, "")
            else -> return null
        }
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private fun sectionNames(name: String): List<String> =
        name.split('/', '／').map { it.trim() }.filter { it.isNotEmpty() }

    private fun draftOf(
        text: String,
        type: MemoryType,
        scope: MemoryScope,
        projectId: String,
        sessionId: String,
        archivePath: String,
        importance: Float,
        sourceUpdatedAt: Long,
    ): MemoryDraft {
        val stored = text.take(MAX_TEXT)
        return MemoryDraft(
            type = type,
            scope = scope,
            projectId = projectId,
            sessionId = sessionId,
            subject = subjectOf(stored),
            text = stored,
            sourceKind = "compaction",
            sourcePath = archivePath,
            tags = "",
            importance = importance,
            sourceUpdatedAt = sourceUpdatedAt,
        )
    }

    /** 用包名或路径做冲突键：同一个对象的新旧事实可以互相替代。 */
    fun subjectOf(text: String): String {
        packagePattern.find(text)?.let { return "pkg:${it.value}" }
        pathPattern.find(text)?.let { return "path:${it.value}" }
        return ""
    }

    /**
     * 判断两条同 subject 的记忆是否真的互相冲突。
     *
     * 只有“同一类关键值都出现、且完全不重合”才算冲突：例如版本 2.0 vs 1.1、
     * 端口 8080 vs 9090。像“版本 2.0”与“签名用 v2”这种补充说明不算冲突，
     * 应并存而不是替代。
     */
    fun conflicts(oldText: String, newText: String): Boolean {
        val old = keyValues(oldText)
        val new = keyValues(newText)
        return old.indices.any { index ->
            old[index].isNotEmpty() && new[index].isNotEmpty() &&
                old[index].intersect(new[index]).isEmpty()
        }
    }

    private fun keyValues(text: String): List<Set<String>> = listOf(
        versionPattern.findAll(text).map { it.value }.toSet(),
        pathPattern.findAll(text).map { it.value }.toSet(),
        portPattern.findAll(text).map { it.groupValues[1] }.toSet(),
        errorPattern.findAll(text).map { it.value }.toSet(),
    )
}
