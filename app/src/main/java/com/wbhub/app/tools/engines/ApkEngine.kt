package com.wbhub.app.tools.engines

import android.content.Context
import android.os.Build
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.ToolDiagnostic
import com.wbhub.engine.api.EngineApi
import com.wbhub.engine.api.KeyMaterial
import org.json.JSONObject
import java.io.File

/**
 * Facade over the dynamically loaded engine.
 *
 * apktool, apksig, smali and BouncyCastle are not part of the APK; they live in
 * `/storage/emulated/0/WBHub/libs/engine.zip` and are loaded through
 * [EngineLoader]. The app only compiles against `engine-api`, which keeps the
 * APK small and lets the engine be replaced without reinstalling.
 */
class ApkEngine(private val context: Context, private val toolchain: Toolchain) {

    init {
        // apktool reads these from static initialisers without null checks.
        if (System.getProperty("os.name") == null) System.setProperty("os.name", "Linux")
        if (System.getProperty("os.arch") == null) System.setProperty("os.arch", "aarch64")
        if (System.getProperty("os.version") == null) System.setProperty("os.version", Build.VERSION.RELEASE)
        if (System.getProperty("sun.arch.data.model") == null) {
            System.setProperty("sun.arch.data.model", if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "64" else "32")
        }
        if (System.getProperty("user.home") == null) {
            System.setProperty("user.home", AppStorage.root(context).absolutePath)
        }
    }

    data class Metadata(val json: JSONObject)

    data class VerifySummary(
        val verified: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val certSha256: List<String>,
        val errors: List<String>,
    )

    data class Outcome(val text: String, val diagnostics: List<ToolDiagnostic>)

    fun installed(): Boolean = EngineLoader.isInstalled(context)

    fun version(): String? = EngineLoader.load(context)?.version()

    fun info(apk: File): Metadata = Metadata(JSONObject(api().metadata(apk)))

    fun dexClasses(input: File, limit: Int): String = api().dexClasses(input, limit)

    fun decompile(input: File, outDir: File, className: String?): Outcome {
        val result = api().decompile(input, outDir, className)
        return Outcome(result.text, result.diagnostics.toLocal())
    }

    fun soInfo(so: File): String = api().soInfo(so)

    fun soDisassemble(so: File, symbol: String?, offset: Long, count: Int): Outcome {
        val result = api().soDisassemble(so, symbol, offset, count)
        return Outcome(result.text, result.diagnostics.toLocal())
    }

    fun decode(apk: File, outDir: File, sources: Boolean, resources: Boolean): Outcome {
        val result = api().decode(apk, outDir, sources, resources)
        return Outcome(result.text, result.diagnostics.toLocal())
    }

    fun build(decodedDir: File, out: File): Outcome {
        val result = api().build(decodedDir, out)
        return Outcome(result.text, result.diagnostics.toLocal())
    }

    fun align(input: File, output: File, pageSize: Int): String {
        output.parentFile?.mkdirs()
        toolchain.runZipalign(input, output, pageSize)
        return "aligned ${input.name} → ${output.name} (page=${pageSize})"
    }

    fun sign(
        input: File,
        output: File,
        key: SigningKeys.Key,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
    ): String = api().sign(
        input,
        output,
        KeyMaterial(key.alias, key.privateKey, key.certificate),
        v1,
        v2,
        v3,
    )

    fun verify(apk: File): VerifySummary {
        val result = api().verify(apk)
        return VerifySummary(
            verified = result.verified,
            v1 = result.v1,
            v2 = result.v2,
            v3 = result.v3,
            certSha256 = result.certSha256,
            errors = result.errors,
        )
    }

    private fun api(): EngineApi = EngineLoader.load(context)
        ?: throw IllegalStateException("逆向引擎初始化失败（已内置在 APK 中，详见 logcat 的 WBHub 标签）")

    private fun List<com.wbhub.engine.api.Diag>.toLocal(): List<ToolDiagnostic> = map {
        ToolDiagnostic(stage = it.stage, severity = it.severity, message = it.message)
    }
}
