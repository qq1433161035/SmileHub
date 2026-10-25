package com.wbhub.app.tools.external

import android.content.Context
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.ToolDiagnostic
import com.wbhub.app.tools.ToolRegistry
import com.wbhub.app.tools.ToolResult
import com.wbhub.app.tools.ToolRisk
import com.wbhub.app.tools.ToolSource
import com.wbhub.app.tools.ToolSpec
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Tools defined on disk rather than in code.
 *
 * Every subdirectory of `/storage/emulated/0/WBHub/tools` with a `tool.json`
 * becomes one model tool. The script receives its arguments as JSON on stdin,
 * in `WB_ARGS`, and as `WB_ARG_<NAME>` environment variables; stdout is what
 * the model reads, stderr becomes diagnostics. Adding or editing a directory
 * takes effect on the next call without restarting the app.
 */
class ExternalToolSource(private val context: Context) {

    private val root: File get() = AppStorage.tools(context)

    fun load(): List<ToolRegistry.RegisteredTool> {
        seedExamples()
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.sortedBy { it.name }.mapNotNull { dir ->
            runCatching { read(dir) }
                .onFailure { android.util.Log.w("WBHub", "skip tool ${dir.name}: ${it.message}") }
                .getOrNull()
        }
    }

    private fun read(dir: File): ToolRegistry.RegisteredTool? {
        val manifestFile = File(dir, "tool.json")
        if (!manifestFile.isFile) return null
        val json = JSONObject(manifestFile.readText())

        val name = json.optString("name").trim()
        val description = json.optString("description").trim()
        if (name.isEmpty() || description.isEmpty()) return null
        if (name in RESERVED) {
            android.util.Log.w("WBHub", "tool ${dir.name} uses reserved name $name")
            return null
        }

        val entryName = json.optString("entry", "run.sh")
        val entry = File(dir, entryName).canonicalFile
        val dirPath = dir.canonicalPath + File.separator
        if (!entry.path.startsWith(dirPath) || !entry.isFile) return null

        val properties = json.optJSONObject("properties")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.get(it) }
        }.orEmpty()
        val required = json.optJSONArray("required")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val env = json.optJSONObject("env")?.let { obj ->
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.orEmpty()

        val spec = ToolSpec(
            name = name,
            description = description,
            // 外部脚本可以执行任意命令，不能由 tool.json 把自己声明成 READ
            // 从而在 AUTO 模式下免审批。
            risk = runCatching { ToolRisk.valueOf(json.optString("risk", "EXEC").uppercase()) }
                .getOrDefault(ToolRisk.EXEC)
                .let { if (it == ToolRisk.READ) ToolRisk.EXEC else it },
            properties = properties,
            required = required,
            timeoutSeconds = json.optInt("timeout_seconds", 120).coerceIn(1, 3600),
            maxOutputBytes = json.optInt("max_output_bytes", 30_000).coerceIn(1_000, 1_000_000),
        )
        val asRoot = json.optBoolean("root", false)

        return ToolRegistry.RegisteredTool(spec, ToolSource.EXTERNAL) { args ->
            run(dir, entry, asRoot, env, spec, args)
        }
    }

    private fun run(
        dir: File,
        entry: File,
        asRoot: Boolean,
        extraEnv: Map<String, String>,
        spec: ToolSpec,
        args: Map<String, Any?>,
    ): ToolResult {
        // tool.json 的 root:true 也要服从用户的全局 root 开关，不能由脚本自行提权。
        val allowRoot = AppStorage.prefs(context, "wb-agent").getBoolean("expose_root", false)
        val argv = if (asRoot && allowRoot) {
            arrayOf("su", "-c", "sh '${entry.absolutePath.replace("'", "'\\''")}'")
        } else {
            arrayOf("sh", entry.absolutePath)
        }
        val argsJson = JSONObject(args).toString()
        val process = try {
            ProcessBuilder(*argv)
                .directory(dir)
                .also { builder ->
                    builder.environment().apply {
                        put("WB_TOOL_NAME", spec.name)
                        put("WB_TOOL_DIR", dir.absolutePath)
                        put("WB_ROOT", AppStorage.root(context).absolutePath)
                        put("WB_WORKSPACE", AppStorage.workspace(context).absolutePath)
                        put("WB_ARGS", argsJson)
                        put("WB_AAPT2", File(context.applicationInfo.nativeLibraryDir, "libaapt2_exec.so").absolutePath)
                        put("WB_ZIPALIGN", File(context.applicationInfo.nativeLibraryDir, "libzipalign_exec.so").absolutePath)
                        putAll(extraEnv)
                        args.forEach { (key, value) ->
                            val envKey = key.uppercase().replace(Regex("[^A-Z0-9_]"), "_")
                            put("WB_ARG_$envKey", value?.toString().orEmpty())
                        }
                    }
                }
                .start()
        } catch (error: Exception) {
            return ToolResult.error("E_INPUT", "无法启动 ${spec.name}：${error.message}")
        }

        // stdin is the same JSON; a script that prefers parsing can read it
        // instead of dealing with individual environment variables.
        runCatching {
            process.outputStream.use { it.write(argsJson.toByteArray()) }
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val outThread = Thread { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
        val errThread = Thread { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
        outThread.start()
        errThread.start()

        val finished = runCatching { process.waitFor(spec.timeoutSeconds.toLong(), TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) process.destroyForcibly()
        outThread.join(1_000)
        errThread.join(1_000)

        val exit = if (finished) process.exitValue() else -1
        val out = stdout.toString()
        val err = stderr.toString()
        val body = buildString {
            append(out.take(spec.maxOutputBytes))
            if (out.length > spec.maxOutputBytes) append("\n… 输出被截断")
            if (err.isNotBlank()) {
                append("\n[stderr]\n")
                append(err.take(spec.maxOutputBytes))
            }
        }.trim()

        val diagnostics = err.lineSequence()
            .filter { it.isNotBlank() }
            .take(20)
            .map {
                ToolDiagnostic(
                    stage = "stderr",
                    severity = if (finished && exit == 0) "info" else "error",
                    message = it.take(500),
                )
            }
            .toList()

        val summary = when {
            !finished -> "[E_TIMEOUT] ${spec.name} 超时（${spec.timeoutSeconds}s）"
            exit == 0 -> "${spec.name} 完成"
            else -> "${spec.name} 退出码 $exit"
        }
        return ToolResult(
            ok = finished && exit == 0,
            summary = summary,
            text = body,
            diagnostics = diagnostics,
        )
    }

    // ------------------------------------------------------------------ //
    // Two examples make the contract obvious without any documentation:
    // one that only echoes, and one that calls the bundled aapt2.
    // ------------------------------------------------------------------ //

    private fun seedExamples() {
        val marker = File(root, ".samples-seeded")
        if (marker.exists()) return

        val hello = File(root, "hello")
        if (!File(hello, "tool.json").exists()) {
            hello.mkdirs()
            File(hello, "tool.json").writeText(
                """
                {
                  "name": "hello",
                  "description": "示例工具：回显传入的参数，用来确认外部工具目录已经生效。",
                  "risk": "READ",
                  "timeout_seconds": 15,
                  "entry": "run.sh",
                  "properties": {
                    "name": {"type": "string", "description": "要问候的名字"}
                  },
                  "required": []
                }
                """.trimIndent(),
            )
            File(hello, "run.sh").writeText(
                """
                #!/system/bin/sh
                echo "hello ${'$'}{WB_ARG_NAME:-world}"
                echo "tool=${'$'}WB_TOOL_NAME"
                echo "args=${'$'}WB_ARGS"
                """.trimIndent(),
            )
        }

        val apk = File(root, "apk_summary")
        if (!File(apk, "tool.json").exists()) {
            apk.mkdirs()
            File(apk, "tool.json").writeText(
                """
                {
                  "name": "apk_summary",
                  "description": "用内置 aapt2 读取 APK 的包名、版本、应用名、ABI 与权限，适合快速确认样本。",
                  "risk": "READ",
                  "timeout_seconds": 60,
                  "entry": "run.sh",
                  "properties": {
                    "path": {"type": "string", "description": "APK 的绝对路径，例如 /storage/emulated/0/Download/1.apk"}
                  },
                  "required": ["path"]
                }
                """.trimIndent(),
            )
            File(apk, "run.sh").writeText(
                """
                #!/system/bin/sh
                target="${'$'}{WB_ARG_PATH:-}"
                [ -n "${'$'}target" ] || { echo "path is required" >&2; exit 2; }
                [ -f "${'$'}target" ] || { echo "no such file: ${'$'}target" >&2; exit 2; }
                "${'$'}WB_AAPT2" dump badging "${'$'}target" 2>&1 | head -40
                """.trimIndent(),
            )
        }

        runCatching { marker.writeText("1") }
    }

    private companion object {
        /** Names the app already exposes; an external tool may not shadow them. */
        val RESERVED = setOf(
            "read", "write", "edit", "bash",
            "toolchain_status", "project_import", "project_list",
            "apk_info", "apk_decode", "apk_build", "apk_align", "apk_sign", "apk_verify",
            "apk_install", "apk_uninstall", "package_info",
            "app_list", "app_permissions", "app_launch", "app_stop", "app_grant", "app_debug",
            "logcat", "adb_status", "adb_connect", "adb_shell",
            "ui_status", "ui_dump", "ui_act", "ui_wait", "screenshot",
            "memory_save", "memory_update", "memory_search", "memory_list", "memory_forget",
        )
    }
}
