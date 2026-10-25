package com.wbhub.app.agent.memory

/**
 * 记忆入库前的敏感内容过滤。
 *
 * 命中即整条丢弃而不是局部脱敏：被截断的 token 仍然可能泄露，
 * 而一条含密钥的“记忆”本身也没有长期价值。
 */
object MemoryRedactor {

    private val patterns = listOf(
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        // 允许 JSON/YAML 的引号与中文冒号：`"password": "x"`、`密钥: x`。
        Regex(
            "(?i)\\b(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret)\\b" +
                "[\"']?\\s*[:=：]",
        ),
        Regex("(?i)(密钥|密码|口令)\\s*[:=：]"),
        Regex("(?i)\\bauthorization\\b\\s*[:=]?\\s*bearer\\b"),
        Regex("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b"),
        Regex("(?i)\\b(sk|pk)-[A-Za-z0-9_-]{12,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
        Regex("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),
        Regex("\\bAIza[0-9A-Za-z_-]{35}\\b"),
    )

    private val pathBlocklist = listOf(
        "WBHub/secrets/",
        "WBHub/records/",
        "/data/local/tmp/wb-hub/",
    )

    fun isSensitive(text: String): Boolean {
        if (text.isBlank()) return false
        if (pathBlocklist.any { text.contains(it) }) return true
        return patterns.any { it.containsMatchIn(text) }
    }
}
