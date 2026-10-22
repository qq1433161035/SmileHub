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
 * 验证内置的 dex 反编译（jadx）和 so 反汇编（Capstone）在真机上可用。
 *
 * .so 用系统自带的 libc，dex 用 Download 里的样本；样本缺失时跳过。
 */
@RunWith(AndroidJUnit4::class)
class ReverseToolsTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun elfInfoAndCapstoneDisassembly() {
        val so = File("/system/lib64/libc.so")
        assumeTrue("no libc at ${so.path}", so.isFile)
        val runtime = ToolingRuntime.get(context)

        val info = runtime.executor.execute(
            "so_info",
            mapOf("path" to so.absolutePath),
            sessionId = "smoke",
            approval = "TEST",
        )
        println(info.render())
        assertTrue(info.render(), info.ok)
        assertTrue(info.render(), info.render().contains("AArch64"))

        val disasm = runtime.executor.execute(
            "so_disassemble",
            mapOf("path" to so.absolutePath, "symbol" to "strlen", "count" to 8),
            sessionId = "smoke",
            approval = "TEST",
        )
        println(disasm.render())
        assertTrue(disasm.render(), disasm.ok)
        assertTrue(disasm.render(), disasm.render().contains("strlen"))
    }

    @Test
    fun jadxDecompilesOneClass() {
        val apk = File("/storage/emulated/0/Download/1.apk")
        assumeTrue("no sample at ${apk.path}", apk.isFile)
        val runtime = ToolingRuntime.get(context)

        val list = runtime.executor.execute(
            "dex_list",
            mapOf("path" to apk.absolutePath, "limit" to 20),
            sessionId = "smoke",
            approval = "TEST",
        )
        println(list.render())
        assertTrue(list.render(), list.ok)

        val classes = list.render().lineSequence()
            .filter { it.trimStart().startsWith("L") && it.contains(";") }
            .toList()
        val line = classes.firstOrNull { !it.contains('$') } ?: classes.firstOrNull()
            ?: error("no class listed:\n${list.render()}")
        val dexType = line.trim().substringBefore(" ").substringBefore("(").substringBefore("$")
        val className = dexType.removePrefix("L").removeSuffix(";").replace('/', '.')

        val decompile = runtime.executor.execute(
            "dex_decompile",
            mapOf(
                "path" to apk.absolutePath,
                "class_name" to className,
                "out" to "smoke/decompile",
            ),
            sessionId = "smoke",
            approval = "TEST",
        )
        println(decompile.render())
        assertTrue(decompile.render(), decompile.ok)
        assertTrue(
            "expected a .java file for $className",
            File(runtime.workspace.root, "smoke/decompile")
                .walkTopDown()
                .any { it.isFile && it.name.endsWith(".java") },
        )
    }
}
