package com.wbhub.app.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MemoryCoreTest {

    @Test
    fun embeddingIsDeterministicAndNormalized() {
        val text = "目标包名 com.example.app，目录 /sdcard/WBHub/workspace/demo"
        val first = LocalEmbedding.embed(text)
        val second = LocalEmbedding.embed(text)
        assertEquals(first.size, second.size)
        for (i in first.indices) assertEquals(first[i], second[i], 0.0f)

        val norm = first.sumOf { (it * it).toDouble() }
        assertTrue("norm=$norm", abs(norm - 1.0) < 1e-4)
    }

    @Test
    fun similarTextScoresHigherThanUnrelated() {
        val query = LocalEmbedding.embed("com.example.app 的签名失败")
        val near = LocalEmbedding.embed("上次签名失败：com.example.app 需要先 zipalign")
        val far = LocalEmbedding.embed("用户喜欢喝乌龙茶")
        assertTrue(LocalEmbedding.cosine(query, near) > LocalEmbedding.cosine(query, far))
    }

    @Test
    fun tokensKeepPathsAndSplitChinese() {
        val tokens = LocalEmbedding.tokens("包名 com.example.app，路径 /sdcard/WBHub/bin")
        assertTrue(tokens.contains("com.example.app"))
        assertTrue(tokens.any { it.startsWith("sdcard/wbhub") })
        assertTrue(tokens.contains("包名"))
    }

    @Test
    fun extractorClassifiesSummarySections() {
        val summary = """
            【历史摘要】
            目标：
            - 修复 com.example.app 的启动崩溃
            已完成：
            - 已定位到签名校验失败
            关键产物：
            - 反编译目录 /sdcard/WBHub/workspace/demo
            关键事实 / 约束：
            - 必须先 zipalign 再签名
            未完成 / 下一步：
            - 重新打包并验证
        """.trimIndent()

        val drafts = MemoryExtractor.fromSummary(summary, "s1", "", "/tmp/archive.json")
        assertTrue(drafts.any { it.type == MemoryType.SEMANTIC })
        assertTrue(drafts.any { it.type == MemoryType.EPISODIC })
        assertTrue(drafts.any { it.text.contains("zipalign") })
        assertTrue(drafts.any { it.subject == "pkg:com.example.app" })
    }

    @Test
    fun redactorDropsSecretsAndKeepsFacts() {
        assertTrue(MemoryRedactor.isSensitive("Authorization: Bearer abcdef123456"))
        assertTrue(MemoryRedactor.isSensitive("api_key=sk-1234567890abcdef"))
        assertTrue(MemoryRedactor.isSensitive("路径 /data/user/0/com.wbhub.app/files/WBHub/secrets/signing"))
        assertFalse(MemoryRedactor.isSensitive("目标包名 com.example.app"))
    }

    @Test
    fun redactorCatchesJsonKeysAndCloudTokens() {
        assertTrue(MemoryRedactor.isSensitive("\"password\": \"hunter2\""))
        assertTrue(MemoryRedactor.isSensitive("\"token\": \"abc123456789\""))
        assertTrue(MemoryRedactor.isSensitive("密钥: abcdef"))
        assertTrue(MemoryRedactor.isSensitive("AKIAABCDEFGHIJKLMNOP"))
        assertTrue(MemoryRedactor.isSensitive("ghp_abcdefghijklmnopqrstuvwx"))
        assertTrue(MemoryRedactor.isSensitive("xoxb-1234567890-abcdef"))
        assertTrue(MemoryRedactor.isSensitive("AIzaSyA1234567890abcdefghijklmnopqrstuv"))
        assertTrue(MemoryRedactor.isSensitive("relative WBHub/secrets/default.p12"))
    }

    @Test
    fun conflictsOnlyWhenKeyValuesDisagree() {
        assertTrue(
            MemoryExtractor.conflicts(
                "包名 com.example.app，版本 2.0",
                "包名 com.example.app，版本 1.1",
            ),
        )
        assertTrue(
            MemoryExtractor.conflicts(
                "输出目录 /sdcard/WBHub/out/a",
                "输出目录 /sdcard/WBHub/out/b",
            ),
        )
        assertFalse(
            MemoryExtractor.conflicts(
                "包名 com.example.app，版本 2.0",
                "包名 com.example.app，签名用 v2",
            ),
        )
    }

    @Test
    fun extractorParsesMarkdownAndNumberedLists() {
        val summary = """
            ## 目标
            1. 修复 com.example.app 崩溃
            ## 关键产物
            * 目录 /sdcard/WBHub/workspace/demo
        """.trimIndent()
        val drafts = MemoryExtractor.fromSummary(summary, "s1", "", "")
        assertTrue(drafts.any { it.type == MemoryType.EPISODIC && it.text.contains("崩溃") })
        assertTrue(drafts.any { it.type == MemoryType.SEMANTIC && it.text.contains("/sdcard") })
    }

    @Test
    fun normalizationMakesCaseAndWidthConsistent() {
        assertEquals(
            LocalEmbedding.normalize("Package  NAME"),
            LocalEmbedding.normalize("ｐａｃｋａｇｅ name"),
        )
    }

    @Test
    fun expiryDefaultsMatchTypes() {
        assertEquals(0L, MemoryType.defaultTtl(MemoryType.PROFILE))
        assertEquals(0L, MemoryType.defaultTtl(MemoryType.PROCEDURAL))
        assertTrue(MemoryType.defaultTtl(MemoryType.SEMANTIC) > MemoryType.defaultTtl(MemoryType.EPISODIC))
    }
}
