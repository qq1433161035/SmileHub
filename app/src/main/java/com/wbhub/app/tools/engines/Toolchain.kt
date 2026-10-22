package com.wbhub.app.tools.engines

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Locates the aarch64 binaries that the Java libraries shell out to.
 *
 * aapt2 and zipalign are static aarch64 executables packaged as `lib*_exec.so`
 * so the installer extracts them into `nativeLibraryDir`, the one directory
 * Android still allows an app to execute from.
 */
class Toolchain(private val context: Context) {

    /** Where the packaged executables land after install. */
    private val nativeDir: File = File(context.applicationInfo.nativeLibraryDir)

    fun aapt2(): File? = tool("libaapt2_exec.so")

    fun zipalign(): File? = tool("libzipalign_exec.so")

    fun available(): Boolean = aapt2() != null && zipalign() != null

    fun describe(): String = buildString {
        append("aapt2=")
        append(aapt2()?.let { "arm64 (${it.length()} bytes)" } ?: "missing")
        append(", zipalign=")
        append(zipalign()?.let { "arm64 (${it.length()} bytes)" } ?: "missing")
    }

    fun runZipalign(input: File, output: File, pageSize: Int, timeoutSeconds: Int = 120): String {
        val binary = zipalign() ?: throw IllegalStateException("zipalign is not packaged for this device")
        // zipalign's positional alignment is always 4 bytes; -P selects the page
        // size used for uncompressed .so files.
        val argv = mutableListOf(binary.absolutePath, "-f")
        if (pageSize >= 16_384) {
            argv += listOf("-P", "16")
        } else {
            argv += "-p"
        }
        argv += listOf("4", input.absolutePath, output.absolutePath)
        val process = ProcessBuilder(argv)
            .redirectErrorStream(true)
            .start()
        val outputText = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw IllegalStateException("zipalign timed out after ${timeoutSeconds}s")
        }
        if (process.exitValue() != 0) {
            throw IllegalStateException("zipalign failed (${process.exitValue()}): $outputText")
        }
        return outputText
    }

    private fun tool(name: String): File? {
        val file = File(nativeDir, name)
        return file.takeIf { it.isFile }
    }
}
