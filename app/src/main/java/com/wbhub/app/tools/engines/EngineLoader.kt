package com.wbhub.app.tools.engines

import android.content.Context
import android.util.Log
import com.wbhub.app.storage.AppStorage
import com.wbhub.engine.EngineImpl
import com.wbhub.engine.api.EngineApi
import java.io.File

/**
 * Creates the reverse-engineering engine.
 *
 * apktool, apksig, smali, BouncyCastle, jadx and Capstone are compiled into the
 * APK, so there is nothing to download or load dynamically: this only wires the
 * engine to the executable aapt2 and the apktool framework directory.
 */
object EngineLoader {

    @Volatile
    private var instance: EngineApi? = null

    fun load(context: Context): EngineApi? {
        instance?.let { return it }
        return synchronized(this) {
            val existing = instance
            if (existing != null) return@synchronized existing
            val created = runCatching {
                val aapt2 = Toolchain(context).aapt2()?.absolutePath.orEmpty()
                val framework = File(AppStorage.root(context), "apktool/framework").apply { mkdirs() }
                installFramework(framework)
                EngineImpl(context.applicationContext, aapt2, framework.absolutePath)
            }.onFailure {
                Log.e("WBHub", "engine init failed", it)
            }.getOrNull()
            if (created != null) instance = created
            created
        }
    }

    fun isInstalled(context: Context): Boolean = true

    /**
     * apktool looks for the Android framework as `1.apk` inside its framework
     * directory. The jar is a Java resource of apktool-lib, so it ships inside
     * the APK and is unpacked here on first use.
     */
    private fun installFramework(frameworkDir: File) {
        frameworkDir.mkdirs()
        val target = File(frameworkDir, "1.apk")
        if (target.isFile && target.length() > 0) return
        runCatching {
            val input = EngineImpl::class.java.getResourceAsStream(FRAMEWORK_RESOURCE)
                ?: error("$FRAMEWORK_RESOURCE missing from the APK")
            input.use { source ->
                target.outputStream().use { output -> source.copyTo(output) }
            }
        }.onFailure { Log.w("WBHub", "framework install failed", it) }
    }

    private const val FRAMEWORK_RESOURCE = "/brut/androlib/android-framework.jar"
}
