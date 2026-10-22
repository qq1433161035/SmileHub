package com.wbhub.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.wbhub.app.tools.ToolingRuntime
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device checks for the tooling stack.
 *
 * The whole point of P0 is proving apktool/aapt2/apksig actually run on ART,
 * which a desktop unit test cannot answer. The round trip uses this app's own
 * APK as the sample.
 */
@RunWith(AndroidJUnit4::class)
class ToolingSmokeTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun packagedBinariesExecute() {
        val runtime = ToolingRuntime.get(context)
        assertTrue("toolchain missing: ${runtime.toolchain.describe()}", runtime.toolchain.available())

        val aapt2 = runtime.toolchain.aapt2()!!
        assertTrue("aapt2 not executable: ${aapt2.absolutePath}", aapt2.canExecute())
        val process = ProcessBuilder(aapt2.absolutePath, "version")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        assertTrue("aapt2 exited $code: $output", code == 0 && output.contains("Android Asset Packaging Tool"))
    }

    @Test
    fun readsDownloadApk() {
        // 真机上的共享存储样本；没有就跳过，不让测试依赖用户文件。
        val download = File("/storage/emulated/0/Download/1.apk")
        assumeTrue("no sample at ${download.path}", download.isFile)
        val runtime = ToolingRuntime.get(context)
        val result = runtime.executor.execute(
            "apk_info",
            mapOf("path" to download.absolutePath),
            sessionId = "smoke",
            approval = "TEST",
        )
        println(result.render())
        assertTrue(result.render(), result.ok)
    }

    @Test
    fun decodeBuildSignRoundTrip() {
        val runtime = ToolingRuntime.get(context)
        val staged = File(runtime.workspace.staging(), "fixture.apk")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.apk").use { input ->
            staged.outputStream().use { output -> input.copyTo(output) }
        }
        assertTrue("fixture missing", staged.isFile)

        val decode = runtime.executor.execute(
            "apk_decode",
            mapOf("path" to staged.absolutePath, "dest" to "smoke/decode"),
            sessionId = "smoke",
            approval = "TEST",
        )
        assertTrue(decode.render(), decode.ok)

        val build = runtime.executor.execute(
            "apk_build",
            mapOf("dir" to "smoke/decode", "out" to "smoke/out/unsigned.apk"),
            sessionId = "smoke",
            approval = "TEST",
        )
        assertTrue(build.render(), build.ok)

        val sign = runtime.executor.execute(
            "apk_sign",
            mapOf("path" to "smoke/out/unsigned.apk", "out" to "smoke/out/signed.apk"),
            sessionId = "smoke",
            approval = "TEST",
        )
        assertTrue(sign.render(), sign.ok)

        val verify = runtime.executor.execute(
            "apk_verify",
            mapOf("path" to "smoke/out/signed.apk"),
            sessionId = "smoke",
            approval = "TEST",
        )
        assertTrue(verify.render(), verify.ok)
    }
}
