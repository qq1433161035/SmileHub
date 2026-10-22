package com.wbhub.engine.api

import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * The contract between the app and the reverse-engineering engine.
 *
 * The implementation is compiled into the APK as well; the interface only keeps
 * the tool layer free of apktool/jadx/Capstone types.
 */
interface EngineApi {

    /** APK metadata as JSON, because the engine has no UI model of its own. */
    fun metadata(apk: File): String

    /** 列出 dex（或 APK 内所有 dex）的类与方法，纯文本。 */
    fun dexClasses(input: File, limit: Int): String

    /** 用 jadx 把 dex/APK 反编译为 Java；[className] 为空时导出全量。 */
    fun decompile(input: File, outDir: File, className: String?): Outcome

    /** ELF 结构摘要：架构、段、导入导出符号。 */
    fun soInfo(so: File): String

    /** 反汇编 .so，按符号名或虚拟地址定位。 */
    fun soDisassemble(so: File, symbol: String?, offset: Long, count: Int): Outcome

    fun decode(apk: File, outDir: File, sources: Boolean, resources: Boolean): Outcome

    fun build(decodedDir: File, out: File): Outcome

    fun sign(
        input: File,
        output: File,
        key: KeyMaterial,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
    ): String

    fun verify(apk: File): Verify

    /** Loads the signing key from [keysDir], generating it on first use. */
    fun loadOrCreateKey(keysDir: File, id: String): KeyMaterial

    fun version(): String
}

data class Outcome(val text: String, val diagnostics: List<Diag>)

data class Diag(val stage: String, val severity: String, val message: String)

data class Verify(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val certSha256: List<String>,
    val errors: List<String>,
)

data class KeyMaterial(
    val alias: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)
