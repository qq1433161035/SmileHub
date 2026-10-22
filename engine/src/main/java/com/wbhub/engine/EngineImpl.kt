package com.wbhub.engine

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import brut.androlib.ApkBuilder
import brut.androlib.ApkDecoder
import brut.androlib.Config
import brut.directory.ExtFile
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.wbhub.engine.api.Diag
import com.wbhub.engine.api.EngineApi
import com.wbhub.engine.api.KeyMaterial
import com.wbhub.engine.api.Outcome
import com.wbhub.engine.api.Verify
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * The implementation that is loaded from `/WBHub/libs/engine.zip`.
 *
 * Only the app's `EngineApi` types cross the class-loader boundary; everything
 * else — apktool, apksig, BouncyCastle — lives in this dex and never ships in
 * the APK.
 */
class EngineImpl(
    private val context: Context,
    private val aapt2Path: String,
    private val frameworkDir: String,
) : EngineApi {

    override fun metadata(apk: File): String {
        var flags = PackageManager.GET_PERMISSIONS or
            PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS
        @Suppress("DEPRECATION")
        flags = flags or if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: throw IllegalArgumentException("not an APK: ${apk.name}")
        val app = info.applicationInfo
        app?.sourceDir = apk.absolutePath
        app?.publicSourceDir = apk.absolutePath
        val label = runCatching { app?.loadLabel(context.packageManager)?.toString() }
            .getOrNull()
            .orEmpty()

        val dexFiles = mutableListOf<String>()
        val abis = sortedSetOf<String>()
        val libs = mutableListOf<String>()
        var entries = 0
        ZipFile(apk).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                entries++
                val name = entry.name
                if (name.matches(Regex("classes[0-9]*\\.dex"))) dexFiles += name
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    libs += name
                    name.split('/').getOrNull(1)?.let { abis += it }
                }
            }
        }

        val certHashes = signingCertificates(info)

        return JSONObject().apply {
            put("label", label)
            put("package", info.packageName)
            put("versionName", info.versionName ?: "")
            put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
            put("minSdk", app?.minSdkVersion ?: 0)
            put("targetSdk", app?.targetSdkVersion ?: 0)
            put("abis", JSONArray(abis.toList()))
            put("permissions", JSONArray(info.requestedPermissions?.toList() ?: emptyList<String>()))
            put("dexFiles", JSONArray(dexFiles.sorted()))
            put("nativeLibs", JSONArray(libs))
            put("entries", entries)
            put("sizeBytes", apk.length())
            put("sha256", sha256(apk))
            put(
                "signature",
                JSONObject().apply {
                    put("certSha256", JSONArray(certHashes))
                    put("signed", certHashes.isNotEmpty())
                },
            )
            put("activities", JSONArray(info.activities?.map { it.name } ?: emptyList<String>()))
        }.toString()
    }

    override fun dexClasses(input: File, limit: Int): String {
        val opcodes = Opcodes.forApi(35)
        val dexFiles = if (input.name.endsWith(".dex", ignoreCase = true)) {
            listOf(DexFileFactory.loadDexFile(input, opcodes))
        } else {
            val container = DexFileFactory.loadDexContainer(input, opcodes)
            container.dexEntryNames.mapNotNull { name ->
                runCatching { container.getEntry(name)?.dexFile }.getOrNull()
            }
        }

        val text = StringBuilder()
        var classes = 0
        var methods = 0
        for (dex in dexFiles) {
            for (classDef in dex.classes) {
                if (classes >= limit || text.length > MAX_TEXT) break
                val methodList = classDef.methods.toList()
                methods += methodList.size
                text.append(classDef.type.replace('/', '.')).append("  (")
                    .append(methodList.size).append(" methods)\n")
                methodList.take(200).forEach { method ->
                    text.append("    ").append(method.name).append('(')
                        .append(method.parameterTypes.joinToString(", "))
                        .append(')').append(method.returnType).append('\n')
                }
                classes++
            }
        }
        text.append("\n共 $classes 个类（上限 $limit），$methods 个方法")
        return text.toString()
    }

    override fun decompile(input: File, outDir: File, className: String?): Outcome {
        outDir.mkdirs()
        // APK 输入先拆出 classes*.dex：jadx 只有在看到 resources.arsc 时才会去
        // 解析 AndroidManifest，而 Android 的 XML 解析器不支持它设置的安全特性。
        // 反编译代码本身不需要资源，所以直接喂 dex 可以绕开整条资源链路。
        val extracted = if (input.name.endsWith(".dex", ignoreCase = true)) {
            null
        } else {
            File(outDir, ".dex-input")
        }
        val inputs = if (extracted == null) {
            listOf(input)
        } else {
            extractDexFiles(input, extracted)
        }
        try {
            val args = JadxArgs().apply {
                setInputFiles(inputs)
                setOutDir(outDir)
                setOutputFormat(JadxArgs.OutputFormatEnum.JAVA)
                setShowInconsistentCode(false)
                setSkipResources(true)
            }
            synchronized(JADX_LOCK) {
                JadxDecompiler(args).use { jadx ->
                    jadx.load()
                    if (className.isNullOrBlank()) {
                        jadx.save()
                        return Outcome(
                            "decompiled ${jadx.classes.size} classes → ${outDir.absolutePath}",
                            emptyList(),
                        )
                    }
                    val name = className.trim()
                    val target = jadx.searchJavaClassByOrigFullName(name)
                        ?: jadx.searchJavaClassOrItsParentByOrigFullName(name)
                        ?: jadx.classesWithInners.firstOrNull { it.fullName == name || it.name == name }
                        ?: throw IllegalArgumentException("未找到类：$name")
                    val code = target.code
                    val file = File(outDir, target.fullName.replace('.', '/') + ".java")
                    file.parentFile?.mkdirs()
                    file.writeText(code)
                    return Outcome(
                        "decompiled ${target.fullName} (${code.length} chars) → ${file.absolutePath}",
                        emptyList(),
                    )
                }
            }
        } finally {
            extracted?.deleteRecursively()
        }
    }

    private fun extractDexFiles(apk: File, target: File): List<File> {
        target.deleteRecursively()
        target.mkdirs()
        val files = mutableListOf<File>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }
                .sortedBy { it.name }
                .forEach { entry ->
                    val out = File(target, entry.name)
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    files += out
                }
        }
        require(files.isNotEmpty()) { "${apk.name} 中没有 classes*.dex" }
        return files
    }

    override fun soInfo(so: File): String = ElfFile(so).use { it.describe() }

    override fun soDisassemble(so: File, symbol: String?, offset: Long, count: Int): Outcome {
        val wanted = count.coerceIn(1, 2000)
        return ElfFile(so).use { elf ->
            val target = symbol?.takeIf { it.isNotBlank() }?.let {
                elf.symbol(it) ?: throw IllegalArgumentException("未找到符号：$it")
            }
            val start = target?.value ?: offset
            require(start > 0) { "请提供 symbol 或虚拟地址 offset" }
            val fileOffset = elf.vaddrToOffset(start)
                ?: throw IllegalArgumentException("0x${start.toString(16)} 不在任何可加载段内")
            val available = if (target != null && target.size > 0) {
                target.size
            } else {
                elf.availableAt(start)
            }
            val maxBytes = minOf(
                if (available > 0) available else wanted.toLong() * 15,
                wanted.toLong() * 15,
            ).coerceAtMost(MAX_DISASM_BYTES).toInt()
            val bytes = elf.readAt(fileOffset, maxBytes)
            val text = NativeDisasm.disasm(bytes, start, wanted, elf.capstoneArch())
                ?: throw IllegalStateException("Capstone 初始化失败（${elf.archName()}）")
            val header = buildString {
                appendLine("; ${so.name} ${elf.archName()} @ 0x${start.toString(16)}")
                if (target != null) appendLine("; symbol ${target.name} size=${target.size}")
                appendLine("; ${wanted} 条指令，读取 ${bytes.size} 字节")
            }
            Outcome(header + text, emptyList())
        }
    }

    override fun decode(apk: File, outDir: File, sources: Boolean, resources: Boolean): Outcome {
        val config = config()
        config.forceDelete = true
        config.setDecodeSources(if (sources) Config.DECODE_SOURCES_SMALI else Config.DECODE_SOURCES_NONE)
        config.setDecodeResources(if (resources) Config.DECODE_RESOURCES_FULL else Config.DECODE_RESOURCES_NONE)
        val captured = mutableListOf<Diag>()
        val text = captureApktoolLogs(captured) {
            ApkDecoder(config, apk).decode(outDir)
            "decoded ${apk.name} → ${outDir.name} (smali=$sources, resources=$resources)"
        }
        return Outcome(text, captured)
    }

    override fun build(decodedDir: File, out: File): Outcome {
        val config = config()
        config.forceBuildAll = true
        val needsAapt2 = File(decodedDir, "res").isDirectory
        if (needsAapt2 && !File(aapt2Path).isFile) {
            throw IllegalStateException(
                "aapt2 is not packaged for this device; decode with resources=false or provide aapt2",
            )
        }
        val captured = mutableListOf<Diag>()
        val text = captureApktoolLogs(captured) {
            out.parentFile?.mkdirs()
            ApkBuilder(config, ExtFile(decodedDir)).build(out)
            "built ${decodedDir.name} → ${out.name} (${out.length()} bytes)"
        }
        return Outcome(text, captured)
    }

    override fun sign(
        input: File,
        output: File,
        key: KeyMaterial,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
    ): String {
        val signerConfig = ApkSigner.SignerConfig.Builder(
            key.alias,
            key.privateKey,
            listOf(key.certificate),
        ).build()
        output.parentFile?.mkdirs()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .build()
            .sign()
        return "signed ${input.name} → ${output.name} (v1=$v1, v2=$v2, v3=$v3, alias=${key.alias})"
    }

    override fun verify(apk: File): Verify {
        val result = ApkVerifier.Builder(apk).build().verify()
        val certs = result.signerCertificates.map { sha256(it.encoded) }.distinct()
        val errors = result.errors.map { error -> "${error.issue}: $error" }
        return Verify(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            certSha256 = certs,
            errors = errors,
        )
    }

    override fun version(): String =
        "apktool 2.10.0, apksig ${ApkSigner::class.java.`package`?.implementationVersion.orEmpty()}, " +
            "jadx 1.5.6, capstone 5.0.1"

    // ------------------------------------------------------------------ //
    // Signing keys
    // ------------------------------------------------------------------ //

    override fun loadOrCreateKey(keysDir: File, id: String): KeyMaterial {
        keysDir.mkdirs()
        val file = File(keysDir, "$id.p12")
        val passwordFile = File(keysDir, "$id.pass")
        if (file.isFile && passwordFile.isFile) {
            val password = decrypt(passwordFile.readText())
            return load(file, id, password)
        }
        return create(file, passwordFile, id)
    }

    private fun load(file: File, id: String, password: String): KeyMaterial {
        val keyStore = KeyStore.getInstance("PKCS12")
        file.inputStream().use { keyStore.load(it, password.toCharArray()) }
        val alias = keyStore.aliases().toList().firstOrNull()
            ?: throw IllegalStateException("keystore $id has no entries")
        val privateKey = keyStore.getKey(alias, password.toCharArray()) as PrivateKey
        val certificate = keyStore.getCertificate(alias) as X509Certificate
        return KeyMaterial(alias, privateKey, certificate)
    }

    private fun create(file: File, passwordFile: File, id: String): KeyMaterial {
        val password = randomPassword()
        val alias = "wbhub-$id"
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val provider = BouncyCastleProvider()
        val name = X500Name("CN=SmileHub $id, O=SmileHub, C=CN")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - DAY_MS),
            Date(now + 25L * 365 * DAY_MS),
            name,
            pair.public,
        )
        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(
                builder.build(
                    JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private),
                ),
            )

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(alias, pair.private, password.toCharArray(), arrayOf(certificate))
        file.outputStream().use { keyStore.store(it, password.toCharArray()) }
        passwordFile.writeText(encrypt(password))
        return KeyMaterial(alias, pair.private, certificate)
    }

    private fun randomPassword(): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    private fun secretKey(): javax.crypto.SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(VAULT_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                VAULT_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = cipher.doFinal(plain.toByteArray())
        return android.util.Base64.encodeToString(cipher.iv + payload, android.util.Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val payload = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(payload))
    }

    // ------------------------------------------------------------------ //
    // apktool plumbing
    // ------------------------------------------------------------------ //

    private fun config(): Config = Config.getDefaultConfig().apply {
        frameworkDirectory = frameworkDir
        aaptPath = aapt2Path
        useAapt2 = true
        jobs = 2
    }

    private fun <T> captureApktoolLogs(sink: MutableList<Diag>, block: () -> T): T {
        val rootLogger = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() < Level.INFO.intValue()) return
                sink += Diag(
                    stage = "APKTOOL",
                    severity = record.level.name,
                    message = record.message ?: "",
                )
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        rootLogger.addHandler(handler)
        return try {
            block()
        } finally {
            rootLogger.removeHandler(handler)
        }
    }

    private fun signingCertificates(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptyList()
        return signatures.map { sha256(it.toByteArray()) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val VAULT_ALIAS = "wbhub-keystore-vault"
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** 类清单的最大字符数，避免把整包类列表塞进上下文。 */
        const val MAX_TEXT = 120_000

        /** 单次反汇编最多读取 64KB 代码。 */
        const val MAX_DISASM_BYTES = 64L * 1024

        /** jadx 使用全局状态，串行反编译。 */
        val JADX_LOCK = Any()
    }
}
