package com.wbhub.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.wbhub.app.tools.ToolExecutor
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * The four tools the agent can call.
 *
 * Paths are resolved against a working directory rather than the whole file
 * system: a relative path lands inside it, and an absolute path is taken as
 * given. The shell runs through `sh`, escalated to root when that is enabled,
 * which is what lets the agent touch anything the device allows rather than
 * only the app's own sandbox.
 */
class AgentTools(
    private val context: Context,
    private val workDir: File,
    private val useRoot: Boolean,
    /** Tools backed by the tooling stack (APK/DEX/SO/ADB); null when unavailable. */
    private val tooling: ToolExecutor? = null,
    private val sessionId: String? = null,
) {

    /**
     * 私有工具目录。
     *
     * targetSdk 28 下，应用私有目录里的 ELF 可以直接 exec（Termux 同款）；
     * /sdcard 是 noexec，所以可执行文件必须落在这里。
     */
    private val toolRoot: File = File(context.filesDir, "WBHub")

    /** Whatever the model asked for, plus the text that goes back to it. */
    data class Outcome(val text: String, val ok: Boolean, val image: String? = null)

    init {
        // 工作区自带扩展目录：下载的脚本、纯 Python 包、可执行文件各有归处。
        runCatching {
            workDir.mkdirs()
            toolRoot.mkdirs()
            EXTENSION_DIRS.forEach {
                File(toolRoot, it).mkdirs()
                File(workDir, it).mkdirs()
            }
        }
    }

    private val builtinDefinitions: List<Map<String, Any>> = listOf(
        tool(
            name = "read",
            description = "Read a text file and return its contents with line numbers. " +
                "Use offset and limit for large files.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "offset" to intProp("First line to return, 1-based. Defaults to 1."),
                "limit" to intProp("How many lines to return. Defaults to 2000."),
            ),
            required = listOf("path"),
        ),
        tool(
            name = "write",
            description = "Write text to a file, creating it and any missing parent " +
                "directories. Replaces the whole file.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "content" to stringProp("Full file contents to write."),
            ),
            required = listOf("path", "content"),
        ),
        tool(
            name = "edit",
            description = "Replace an exact string in a file. old_text must appear " +
                "exactly once unless replace_all is true.",
            properties = mapOf(
                "path" to stringProp("File path, relative to the working directory or absolute."),
                "old_text" to stringProp("Exact text to find."),
                "new_text" to stringProp("Replacement text."),
                "replace_all" to boolProp("Replace every occurrence instead of requiring a unique match."),
            ),
            required = listOf("path", "old_text", "new_text"),
        ),
        tool(
            name = "view_image",
            description = "Open an image file (png/jpg/webp) so you can actually see it. " +
                "Call this whenever the user gives a path to a screenshot or photo; " +
                "reading an image with read/bash only returns binary garbage.",
            properties = mapOf(
                "path" to stringProp("Image path, relative to the working directory or absolute."),
            ),
            required = listOf("path"),
        ),
        tool(
            name = "python",
            description = "Run Python 3 code on the device and return its stdout/stderr. The " +
                "standard library is bundled, and py_modules/ + site-packages/ (workspace and " +
                "the private tools/ directory) are on sys.path, so pure-Python packages " +
                "downloaded there import directly. " +
                "Pass code for a one-off snippet, or path to run a " +
                ".py file saved in the workspace — that is how reusable Python plugins are kept, " +
                "the same way as shell scripts. ctypes and cffi are blocked: loading native " +
                "libraries in-process can crash the whole app. Use this instead of looking for " +
                "a python binary in bash.",
            properties = mapOf(
                "code" to stringProp("Python source code to execute. Omit when using path."),
                "path" to stringProp(
                    "A .py file to run, relative to the working directory or absolute. " +
                        "Takes precedence over code.",
                ),
                "args" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string"),
                    "description" to "Command-line arguments exposed as sys.argv.",
                ),
                "timeout_seconds" to intProp("Stop waiting after this many seconds. Defaults to 60."),
            ),
            required = emptyList(),
        ),
        tool(
            name = "download",
            description = "Download a file over http(s), so a missing capability can be fetched " +
                "instead of only using built-ins. A path starting with tools/ goes to the " +
                "app-private tool directory (the only place where downloaded ELF binaries can be " +
                "executed on this build); any other path goes to the visible working directory. " +
                "Use tools/bin for executables (prepended to PATH), tools/py_modules with " +
                "unzip=true for pure-Python packages (on sys.path), tools/lib for shared libraries.",
            properties = mapOf(
                "url" to stringProp("http(s) URL to fetch."),
                "path" to stringProp(
                    "Destination relative to the working directory. With unzip=true this is the " +
                        "directory to extract into, e.g. py_modules.",
                ),
                "unzip" to boolProp("Treat the download as a zip and extract it into the path directory."),
                "timeout_seconds" to intProp("Abort when stalled this long. Defaults to 120."),
            ),
            required = listOf("url", "path"),
        ),
        tool(
            name = "bash",
            description = "Run a shell command and return its combined output. The " +
                "working directory is the agent's working directory. Root is used " +
                "when it is enabled. Note: pm/adb run as the app UID and are denied " +
                "without root; use package_info to query packages and apk_install/apk_uninstall " +
                "instead of shell pm. screencap, uiautomator and service call also fail under " +
                "the app UID; when an ADB session is connected, run them through adb_shell. " +
                "There is no python binary in this shell: use the python tool instead. " +
                "The private tools/bin directory is first on PATH.",
            properties = mapOf(
                "command" to stringProp("Shell command to run."),
                "timeout_seconds" to intProp("Kill the command after this many seconds. Defaults to 60."),
            ),
            required = listOf("command"),
        ),
    )

    val definitions: List<Map<String, Any>> = builtinDefinitions + tooling?.definitions().orEmpty()

    /**
     * Runs one tool call. Unknown names come back as a plain error, not a throw.
     *
     * The approval gate lives here rather than in the loop so every path into a
     * tool goes through it, including any future caller.
     */
    fun run(name: String, args: Map<String, Any?>, approval: ApprovalMode, ask: (String) -> Boolean): Outcome {
        val toolingSpec = tooling?.spec(name)
        if (toolingSpec != null) {
            if (ToolPolicy.needsApproval(approval, toolingSpec.risk)) {
                if (!ask(ToolPolicy.summarize(name, args))) {
                    return Outcome("用户拒绝执行：${ToolPolicy.summarize(name, args)}", false)
                }
            }
            val result = tooling.execute(name, args, sessionId, approval.name)
            return Outcome(result.render(), result.ok)
        }
        if (ToolPolicy.needsApproval(approval, name)) {
            if (!ask(ToolPolicy.summarize(name, args))) {
                return Outcome("用户拒绝执行：${ToolPolicy.summarize(name, args)}", false)
            }
        }
        return when (name) {
            "read" -> read(args)
            "write" -> write(args)
            "edit" -> edit(args)
            "view_image" -> viewImage(args)
            "python" -> python(args)
            "download" -> download(args)
            "bash" -> bash(args)
            else -> Outcome("unknown tool: $name", false)
        }
    }

    // ------------------------------------------------------------------ //
    // read
    // ------------------------------------------------------------------ //

    private fun read(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("read needs a path", false)
        if (!file.exists()) return Outcome("no such file: ${file.path}", false)
        if (file.isDirectory) return Outcome("${file.path} is a directory; use bash ls", false)
        val offset = (args.int("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.int("limit") ?: DEFAULT_READ_LINES).coerceIn(1, MAX_READ_LINES)

        return runCatching {
            val lines = file.readLines()
            val slice = lines.drop(offset - 1).take(limit)
            if (slice.isEmpty()) {
                Outcome("(no lines at offset $offset; the file has ${lines.size})", true)
            } else {
                // Line numbers are part of the contract: they are what makes a
                // following edit call able to name an exact location.
                val body = slice.mapIndexed { i, line -> "${offset + i}\t$line" }.joinToString("\n")
                val more = lines.size - (offset - 1) - slice.size
                val tail = if (more > 0) "\n… $more more lines" else ""
                Outcome(body + tail, true)
            }
        }.getOrElse { Outcome("read failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // write
    // ------------------------------------------------------------------ //

    private fun write(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("write needs a path", false)
        val content = args.str("content") ?: return Outcome("write needs content", false)
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            Outcome("wrote ${content.length} chars to ${file.path}", true)
        }.getOrElse { Outcome("write failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // edit
    // ------------------------------------------------------------------ //

    private fun edit(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("edit needs a path", false)
        val oldText = args.str("old_text") ?: return Outcome("edit needs old_text", false)
        val newText = args.str("new_text") ?: return Outcome("edit needs new_text", false)
        val replaceAll = args["replace_all"] == true
        if (oldText.isEmpty()) return Outcome("old_text must not be empty", false)
        if (!file.exists()) return Outcome("no such file: ${file.path}", false)

        return runCatching {
            val original = file.readText()
            val occurrences = countOccurrences(original, oldText)
            when {
                occurrences == 0 -> Outcome("old_text not found in ${file.path}", false)
                // Requiring a unique match is what keeps an edit from silently
                // changing the wrong one of several identical fragments.
                occurrences > 1 && !replaceAll ->
                    Outcome("old_text appears $occurrences times; add more context or set replace_all", false)
                else -> {
                    val updated = if (replaceAll) {
                        original.replace(oldText, newText)
                    } else {
                        original.replaceFirst(oldText, newText)
                    }
                    file.writeText(updated)
                    val what = if (replaceAll) "$occurrences occurrences" else "1 occurrence"
                    Outcome("replaced $what in ${file.path}", true)
                }
            }
        }.getOrElse { Outcome("edit failed: ${it.message}", false) }
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ------------------------------------------------------------------ //
    // view_image
    // ------------------------------------------------------------------ //

    /** 把本地图片读成 data URL，由循环附在一条用户消息里交给模型。 */
    private fun viewImage(args: Map<String, Any?>): Outcome {
        val file = resolve(args.str("path")) ?: return Outcome("view_image needs a path", false)
        if (!file.exists()) return Outcome("no such file: ${file.path}", false)
        if (file.isDirectory) return Outcome("${file.path} is a directory", false)
        return runCatching {
            val bytes = file.readBytes()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return Outcome("not a decodable image: ${file.path}", false)
            val maxSide = 1568
            val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            Outcome(
                "图片已读取并附在下一段上下文里：${file.path}（${bitmap.width}x${bitmap.height}）",
                true,
                image = dataUrl,
            )
        }.getOrElse { Outcome("view_image failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // python
    // ------------------------------------------------------------------ //

    /** 内置 CPython 里执行代码或工作区里的 .py 脚本，输出合并后回给模型。 */
    private fun python(args: Map<String, Any?>): Outcome {
        val script = args.str("path")?.let { path ->
            val file = resolve(path) ?: return Outcome("python needs a valid path", false)
            if (!file.exists()) return Outcome("no such file: ${file.path}", false)
            if (file.isDirectory) return Outcome("${file.path} is a directory", false)
            file
        }
        val code = script?.readText() ?: args.str("code")
            ?: return Outcome("python needs code or path", false)
        val filename = script?.absolutePath ?: "<wbhub>"
        val argv = (args["args"] as? List<*>)
            ?.map { it?.toString().orEmpty() }
            .orEmpty()
        val argvJson = JSONArray(argv).toString()
        // 私有工具目录优先：下载的纯 Python 包解压到 tools/py_modules 即可 import。
        val sysPathJson = JSONArray().apply {
            put(File(toolRoot, "py_modules").absolutePath)
            put(File(toolRoot, "site-packages").absolutePath)
            put(File(workDir, "py_modules").absolutePath)
            put(File(workDir, "site-packages").absolutePath)
        }.toString()
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 300)
        if (!Python.isStarted()) {
            synchronized(PYTHON_LOCK) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            }
        }
        var output = ""
        var failure: Throwable? = null
        val worker = Thread({
            try {
                output = Python.getInstance()
                    .getModule("wbhub_coderunner")
                    .callAttr("run", code, workDir.absolutePath, filename, argvJson, sysPathJson)
                    .toString()
            } catch (error: Throwable) {
                failure = error
            }
        }, "wbhub-python")
        // 解释器无法从外部强杀，超时后只能把线程留在后台。
        worker.isDaemon = true
        worker.start()
        worker.join(timeout * 1000L)
        if (worker.isAlive) return Outcome("python 超时（${timeout}s），代码仍在后台运行", false)
        failure?.let {
            Log.e(TAG, "python failed", it)
            return Outcome("python 启动或执行失败：${it.message}", false)
        }
        val ok = !output.startsWith("Traceback (most recent call last):")
        val text = output.ifBlank { "(no output)" }
        val clipped = if (text.length > MAX_PYTHON_OUTPUT) {
            text.take(MAX_PYTHON_OUTPUT) + "\n… 输出已截断"
        } else {
            text
        }
        return Outcome(clipped, ok)
    }

    // ------------------------------------------------------------------ //
    // download
    // ------------------------------------------------------------------ //

    /** 从网络取一个文件/压缩包，让 Agent 能自己补能力。 */
    private fun download(args: Map<String, Any?>): Outcome {
        val url = args.str("url") ?: return Outcome("download needs url", false)
        val rawPath = args.str("path")?.trim()?.removePrefix("./")
            ?: return Outcome("download needs path", false)
        val inTools = rawPath == "tools" || rawPath.startsWith("tools/")
        val target = if (inTools) {
            File(toolRoot, rawPath.removePrefix("tools").trimStart('/', '\\'))
        } else {
            resolve(rawPath) ?: return Outcome("download needs path", false)
        }
        val canonical = target.canonicalFile
        val allowed = listOf(workDir.canonicalFile, toolRoot.canonicalFile)
            .any { canonical == it || canonical.path.startsWith(it.path + File.separator) }
        if (!allowed) {
            return Outcome("下载只能写进工作区或私有工具目录", false)
        }
        val timeoutSeconds = (args.int("timeout_seconds") ?: 120).coerceIn(5, 600)
        val unzip = args["unzip"] == true
        return runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = timeoutSeconds * 1000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) WorkBuddyHub")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return Outcome("HTTP $code：$url", false)
                if (unzip) {
                    canonical.mkdirs()
                    val tmp = File(workDir, ".download-${System.currentTimeMillis()}.zip")
                    connection.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    val count = runCatching { unzipInto(tmp, canonical) }.getOrElse {
                        tmp.delete()
                        return Outcome("解压失败：${it.message}", false)
                    }
                    tmp.delete()
                    Outcome("已解压 $count 个文件到 ${canonical.path}", true)
                } else {
                    canonical.parentFile?.mkdirs()
                    connection.inputStream.use { input ->
                        canonical.outputStream().use { input.copyTo(it) }
                    }
                    canonical.setExecutable(true, false)
                    Outcome("已下载 ${canonical.path}（${canonical.length()} 字节）", true)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { Outcome("download failed: ${it.message}", false) }
    }

    /** 解压 zip，拒绝把条目写到目标目录之外。 */
    private fun unzipInto(archive: File, destDir: File): Int {
        val root = destDir.canonicalFile
        var count = 0
        ZipInputStream(archive.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(destDir, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    zin.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                    count++
                }
                zin.closeEntry()
            }
        }
        return count
    }

    // ------------------------------------------------------------------ //
    // bash
    // ------------------------------------------------------------------ //

    private fun bash(args: Map<String, Any?>): Outcome {
        val command = args.str("command") ?: return Outcome("bash needs a command", false)
        val timeout = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        val argv = if (useRoot) {
            // -c takes the command as one argument; the shell then expands what
            // is inside it, which is what makes redirection and pipes work.
            arrayOf("su", "-c", command)
        } else {
            arrayOf("sh", "-c", command)
        }

        return runCatching {
            // 私有工具目录的 bin 排在最前：下载的可执行文件可以直接按名字调用。
            val binDirs = listOf(File(toolRoot, "bin"), File(workDir, "bin"))
                .joinToString(":") { it.absolutePath }
            val basePath = System.getenv("PATH").orEmpty()
            val process = ProcessBuilder(*argv)
                .directory(workDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["PATH"] =
                        if (basePath.isBlank()) binDirs else "$binDirs:$basePath"
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching Outcome("(timed out after ${timeout}s)\n$output", false)
            }
            val code = process.exitValue()
            val header = "exit code: $code"
            val limit = output.take(OUTPUT_LIMIT)
            val truncation = if (output.length > OUTPUT_LIMIT) "\n… output truncated" else ""
            Outcome("$header\n$limit$truncation", code == 0)
        }.getOrElse { Outcome("bash failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // Paths
    // ------------------------------------------------------------------ //

    /**
     * Resolves a tool path.
     *
     * A relative path is rooted at the working directory; an absolute one is
     * honoured as written, because the shell tools are expected to reach the
     * whole device once storage access has been granted.
     */
    private fun resolve(path: String?): File? {
        val trimmed = path?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val file = File(trimmed)
        return if (file.isAbsolute) file else File(workDir, trimmed)
    }

    /** Where the shell starts, used by the UI to show the current directory. */
    companion object {
        private const val TAG = "WBHub"
        private const val DEFAULT_READ_LINES = 2000
        private const val MAX_READ_LINES = 20000
        private const val DEFAULT_TIMEOUT_SECONDS = 60
        private const val MAX_TIMEOUT_SECONDS = 600
        private const val OUTPUT_LIMIT = 30_000
        private const val MAX_PYTHON_OUTPUT = 20_000

        /** Python.start 只能成功一次，并发调用要串行。 */
        private val PYTHON_LOCK = Any()

        /** 工作区里的扩展目录，Agent 自己下载的文件按用途存放。 */
        private val EXTENSION_DIRS = listOf("bin", "py_modules", "site-packages", "downloads")

        /** The default place for agent work: a visible folder on shared storage. */
        fun defaultWorkDir(): File =
            File(Environment.getExternalStorageDirectory(), "WBHub")

        /**
         * Whether this device grants root to `su`.
         *
         * Running `su -c id` is the only reliable probe: the binary's presence
         * proves nothing, since it ships on builds where it always refuses. The
         * call may raise the manager's prompt, so it belongs off the main
         * thread and only when the user turns the switch on.
         */
        fun detectRoot(timeoutSeconds: Long = 5): Boolean = runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            output.contains("uid=0")
        }.getOrDefault(false)

        private fun tool(
            name: String,
            description: String,
            properties: Map<String, Any>,
            required: List<String>,
        ): Map<String, Any> = mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to name,
                "description" to description,
                "parameters" to mapOf(
                    "type" to "object",
                    "properties" to properties,
                    "required" to required,
                ),
            ),
        )

        private fun stringProp(description: String) = mapOf("type" to "string", "description" to description)
        private fun intProp(description: String) = mapOf("type" to "integer", "description" to description)
        private fun boolProp(description: String) = mapOf("type" to "boolean", "description" to description)
    }

    private fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    private fun Map<String, Any?>.int(key: String): Int? = when (val value = this[key]) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}
