package com.wbhub.app.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.util.Base64
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.wbhub.app.access.AccessibilityBridge
import com.wbhub.app.agent.memory.MemoryTools
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.ToolExecutor
import com.wbhub.app.tools.ToolRisk
import com.wbhub.app.tools.worker.ToolWorkerClient
import com.wbhub.app.tools.worker.toolNameOf
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
    /**
     * :tools 进程的代理。为 null 时退回本进程执行（旧行为），
     * 这样即便工具进程起不来，工具也不会整体失效。
     */
    private val remote: ToolWorkerClient? = null,
    /** 长期记忆；未开启记忆功能时为 null，工具清单里也不会出现。 */
    private val memory: MemoryTools? = null,
    /** 运行时开关：长任务执行中关掉记忆后，工具调用也要立刻拒绝。 */
    private val memoryEnabled: () -> Boolean = { memory != null },
    /** 父代理的子代理执行器；为 null 时不注册 subagent 工具。 */
    private val subagentRunner: SubagentRunner? = null,
    /**
     * 子代理模式：只暴露并只允许 [SUBAGENT_READ_ONLY_TOOLS]。
     * 这样多个子代理并发时不会写文件或触发审批。
     */
    private val readOnly: Boolean = false,
) {

    /**
     * 当前这次工具调用的进度出口。
     *
     * 工具是顺序执行的，所以单个字段就够；由 [run] 在调用前后设置与清空。
     */
    @Volatile
    private var progressSink: (String) -> Unit = {}

    /** 当前调用的停止判断，供 bash 这类长时间阻塞的工具轮询。 */
    @Volatile
    private var cancelledCheck: () -> Boolean = { false }

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
        // 运行时扩展目录只放在私有工具目录，工作区只存任务产物。
        runCatching {
            workDir.mkdirs()
            toolRoot.mkdirs()
            EXTENSION_DIRS.forEach { File(toolRoot, it).mkdirs() }
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
                "the app UID; when an ADB session is connected, run them through adb_shell, or use " +
                "the screenshot/ui_* tools that go through the accessibility service. " +
                "There is no python binary in this shell: use the python tool instead. " +
                "The private tools/bin directory is first on PATH.",
            properties = mapOf(
                "command" to stringProp("Shell command to run."),
                "timeout_seconds" to intProp("Kill the command after this many seconds. Defaults to 60."),
            ),
            required = listOf("command"),
        ),
    )

    /** 长期记忆工具：只在本进程执行，写入会走现有审批链路。 */
    private val memoryDefinitions: List<Map<String, Any>> = listOf(
        tool(
            name = "memory_save",
            description = "Remember a durable fact for future conversations. Use it for " +
                "confirmed project facts (package names, paths, build conventions), user " +
                "preferences, or reusable procedures. Do not store secrets, transient output " +
                "or anything that is already in the current transcript.",
            properties = mapOf(
                "text" to stringProp("The fact to remember, one or two sentences."),
                "memory_type" to stringProp("profile | semantic | episodic | procedural. Defaults to semantic."),
                "scope" to stringProp("global | project | session. Defaults to global for profile, project otherwise."),
                "tags" to stringProp("Optional comma-separated tags."),
                "importance" to mapOf(
                    "type" to "number",
                    "description" to "0.0 - 1.0, used as a ranking weight. Defaults to 0.6.",
                ),
            ),
            required = listOf("text"),
        ),
        tool(
            name = "memory_update",
            description = "Rewrite an existing memory by id after the user corrects it.",
            properties = mapOf(
                "id" to intProp("Memory id returned by memory_save or memory_search."),
                "text" to stringProp("New text for this memory."),
            ),
            required = listOf("id", "text"),
        ),
        tool(
            name = "memory_search",
            description = "Search long-term memory. Use it when the user references earlier " +
                "sessions or previously confirmed project facts.",
            properties = mapOf(
                "query" to stringProp("What to look for."),
                "top_k" to intProp("How many memories to return, 1-10. Defaults to 5."),
                "memory_type" to stringProp("Optional type filter."),
            ),
            required = listOf("query"),
        ),
        tool(
            name = "memory_list",
            description = "List memories for review, newest first.",
            properties = mapOf(
                "memory_type" to stringProp("Optional type filter."),
                "scope" to stringProp("Optional scope filter: global | project | session."),
                "limit" to intProp("How many rows to return, 1-200. Defaults to 20."),
            ),
            required = emptyList(),
        ),
        tool(
            name = "memory_forget",
            description = "Delete a memory by id. Deleting by query requires force=true and " +
                "affects at most 10 matches.",
            properties = mapOf(
                "id" to intProp("Memory id."),
                "query" to stringProp("Search text; only used when id is omitted."),
                "force" to boolProp("Required when deleting by query."),
            ),
            required = emptyList(),
        ),
    )

    /** 子代理工具：只读并发调研，修改仍由父代理执行。 */
    private val subagentDefinitions: List<Map<String, Any>> = listOf(
        tool(
            name = "subagent",
            description = "Run 1-${SubagentLimits.MAX_TASKS} independent read-only research subtasks " +
                "in parallel. Each sub-agent can read files/images and search long-term memory, but " +
                "cannot write files, run shell/python or change the device. Use it when several " +
                "investigations are independent (different files, packages or call paths). Do not use " +
                "it for edits or a single quick lookup; every sub-agent spends extra model calls. " +
                "Read the reports and apply changes yourself.",
            properties = mapOf(
                "tasks" to mapOf(
                    "type" to "array",
                    "description" to "Independent subtasks, 1-${SubagentLimits.MAX_TASKS} items.",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "title" to stringProp("Short label used in the combined report."),
                            "instruction" to stringProp(
                                "What to investigate and what to report. Include the file paths " +
                                    "or package names the sub-agent should read.",
                            ),
                        ),
                        "required" to listOf("title", "instruction"),
                    ),
                ),
            ),
            required = listOf("tasks"),
        ),
    )

    /**
     * 发给模型的全量工具清单。
     *
     * 无障碍工具留在主进程执行，其余由 :tools 进程发布。工具进程不可用时
     * 整体退化成本地清单，功能不受影响，只是少了一层隔离。
     */
    val definitions: List<Map<String, Any>>
        get() {
            val local = builtinDefinitions +
                (if (memory != null) memoryDefinitions else emptyList()) +
                (if (subagentRunner != null) subagentDefinitions else emptyList())
            val remoteDefinitions = remote?.definitions().orEmpty()
            val all = if (remoteDefinitions.isEmpty()) {
                local + tooling?.definitions().orEmpty()
            } else {
                val localUi = tooling?.definitions().orEmpty().filter { isUiTool(toolNameOf(it)) }
                local +
                    remoteDefinitions.filterNot { isUiTool(toolNameOf(it)) } +
                    localUi
            }
            // 子代理只拿到只读清单；写工具、shell、python 与 subagent 本身都不出现。
            return if (readOnly) {
                all.filter { toolNameOf(it) in SUBAGENT_READ_ONLY_TOOLS }
            } else {
                all
            }
        }

    /**
     * Runs one tool call. Unknown names come back as a plain error, not a throw.
     *
     * The approval gate lives here rather than in the loop so every path into a
     * tool goes through it, including any future caller.
     */
    fun run(
        name: String,
        args: Map<String, Any?>,
        approval: ApprovalMode,
        ask: (String) -> Boolean,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): Outcome {
        progressSink = onProgress
        cancelledCheck = isCancelled
        return try {
            runTool(name, args, approval, ask, onProgress, isCancelled)
        } finally {
            progressSink = {}
            cancelledCheck = { false }
        }
    }

    private fun runTool(
        name: String,
        args: Map<String, Any?>,
        approval: ApprovalMode,
        ask: (String) -> Boolean,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        // 子代理只读：即使模型硬编了一个不在清单里的调用，也在这里拒绝。
        if (readOnly && name !in SUBAGENT_READ_ONLY_TOOLS) {
            return Outcome(
                "[E_SUBAGENT] 子代理只读，不能使用「$name」；需要修改时请把结论交回父代理执行。",
                false,
            )
        }
        // 子代理本身不修改设备，直接执行，不进入审批与远程路由。
        if (name == "subagent") {
            val runner = subagentRunner
                ?: return Outcome("subagent unavailable", false)
            return runSubagent(args, runner, onProgress, isCancelled)
        }
        // 界面调试工具可以单独放行：用户已在设置里给无障碍授权，又打开了
        // 「不再弹窗」（无障碍区块），就不该每一击都再问一次。审计里记 AUTO_UI，
        // 与全局的「允许所有」区分开。
        // 系统设置、安装器、权限弹窗、系统界面等目标即使开了「不再弹窗」也要问一次。
        val guardedUi = uiTargetGuarded(name, args)
        val exempt = uiToolExempt(name) && !guardedUi
        val approvalLabel = if (exempt) "AUTO_UI" else approval.name
        val remoteClient = remote
        // 装工具链、往 PATH 塞可执行文件、改写工具目录：这些即使选了「允许所有」
        // 也要单独问一次，避免一次任务把整个运行环境改坏。
        val envChange = !exempt && !isMemoryTool(name) &&
            EnvironmentGuard.enabled(context) &&
            EnvironmentGuard.isEnvironmentChange(name, args)

        // 无障碍工具必须留在主进程：AccessibilityService 绑定在哪，节点树就在哪。
        // 记忆库也只由主进程持有，跨进程没有意义。
        if (isUiTool(name) || isMemoryTool(name) || remoteClient == null) {
            return runLocal(
                name, args, approval, approvalLabel, exempt, envChange, guardedUi,
                ask, onProgress, isCancelled,
            )
        }

        val risk = if (exempt) null else remoteClient.risk(name)
        val needed = !exempt && (
            guardedUi || envChange ||
                (if (risk != null) {
                    ToolPolicy.needsApproval(approval, risk)
                } else {
                    ToolPolicy.needsApproval(approval, name)
                })
            )
        if (needed) {
            val summary = summarizeFor(name, args, envChange, guardedUi)
            if (!ask(summary)) return Outcome("用户拒绝执行：$summary", false)
        }
        val outcome = remoteClient.execute(
            name = name,
            args = args,
            sessionId = sessionId,
            approval = approvalLabel,
            workDir = workDir,
            useRoot = useRoot,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        if (!outcome.text.startsWith("[${ToolWorkerClient.CODE_UNAVAILABLE}]")) {
            return Outcome(outcome.text, outcome.ok, outcome.image)
        }
        // 工具进程没起来时，只有只读工具可以退回本进程重跑。
        // 写文件、安装、执行命令重跑一次会变成“做两遍”，比直接失败更危险。
        val readOnly = name == "read" || name == "view_image" || risk == ToolRisk.READ
        if (!readOnly) {
            return Outcome(
                "[${ToolWorkerClient.CODE_UNAVAILABLE}] 工具进程不可用，已拒绝在主进程重跑「$name」" +
                    "（避免重复副作用）。稍后重试或重启应用。",
                false,
            )
        }
        return runLocal(
            name, args, approval, approvalLabel, exempt, envChange, guardedUi,
            ask, onProgress, isCancelled,
        )
    }

    /** 解析并执行 subagent 调用；参数非法时返回可读错误，不抛异常。 */
    private fun runSubagent(
        args: Map<String, Any?>,
        runner: SubagentRunner,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val tasks = subagentTasksFrom(args["tasks"])
        if (tasks.isEmpty()) {
            return Outcome("subagent 至少需要一个带 instruction 的任务", false)
        }
        if (isCancelled()) return Outcome("已停止：未启动子代理", false)
        val report = runCatching { runner.run(tasks, onProgress, isCancelled) }
            .getOrElse { error -> SubagentReport("子代理执行失败：${error.message}", false) }
        return Outcome(report.text.take(SubagentLimits.MAX_TOTAL_REPORT_CHARS), report.ok)
    }

    /** 本进程执行：无障碍工具的正常路径，也是只读工具进程不可用时的兜底。 */
    private fun runLocal(
        name: String,
        args: Map<String, Any?>,
        approval: ApprovalMode,
        approvalLabel: String,
        exempt: Boolean,
        envChange: Boolean,
        guardedUi: Boolean,
        ask: (String) -> Boolean,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Outcome {
        val toolingSpec = tooling?.spec(name)
        val needed = !exempt && (
            guardedUi || envChange ||
                (if (toolingSpec != null) {
                    ToolPolicy.needsApproval(approval, toolingSpec.risk)
                } else {
                    ToolPolicy.needsApproval(approval, name)
                })
            )
        if (needed) {
            val summary = summarizeFor(name, args, envChange, guardedUi)
            if (!ask(summary)) return Outcome("用户拒绝执行：$summary", false)
        }
        if (isMemoryTool(name)) {
            val runtime = memory
            if (runtime == null || !memoryEnabled()) {
                return Outcome("长期记忆未开启，无法使用 $name", false)
            }
            val result = runtime.call(name, args, sessionId.orEmpty())
            return Outcome(result.text, result.ok)
        }
        if (toolingSpec != null) {
            val result = tooling.execute(name, args, sessionId, approvalLabel, onProgress, isCancelled)
            return Outcome(result.render(), result.ok)
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
            // 流式读取：不再把整个文件读进内存，超长文件只保留所需行。
            val body = StringBuilder()
            var lineNo = 0
            var emitted = 0
            var remaining = 0
            file.bufferedReader().use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    lineNo++
                    if (lineNo < offset) continue
                    if (emitted >= limit || body.length >= MAX_READ_CHARS) {
                        remaining++
                        continue
                    }
                    // 单行也设上限，避免压缩包/日志里没有换行的超大行撑爆内存。
                    val line = if (raw.length > MAX_LINE_CHARS) {
                        raw.take(MAX_LINE_CHARS) + " …[line truncated]"
                    } else {
                        raw
                    }
                    if (emitted > 0) body.append('\n')
                    body.append(lineNo).append('\t').append(line)
                    emitted++
                }
            }
            if (emitted == 0) {
                Outcome("(no lines at offset $offset; the file has $lineNo)", true)
            } else {
                // Line numbers are part of the contract: they are what makes a
                // following edit call able to name an exact location.
                val tail = if (remaining > 0) "\n… $remaining more lines" else ""
                Outcome(body.toString() + tail, true)
            }
        }.getOrElse { Outcome("read failed: ${it.message}", false) }
    }

    // ------------------------------------------------------------------ //
    // write
    // ------------------------------------------------------------------ //

    private fun write(args: Map<String, Any?>): Outcome {
        val file = resolveWritable(args.str("path"))
            ?: return outsideWorkspace(args.str("path"))
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
        val file = resolveWritable(args.str("path"))
            ?: return outsideWorkspace(args.str("path"))
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

    /** 无障碍工具：依赖绑定在本进程的 AccessibilityService，不能跨进程。 */
    private fun isUiTool(name: String?): Boolean = name != null && name in UI_TOOLS

    /** 记忆工具：数据库只在主进程打开，同样不能跨进程。 */
    private fun isMemoryTool(name: String?): Boolean = name != null && name in MEMORY_TOOLS

    /** 审批弹窗的文案；环境变更/敏感界面单独加前缀，让用户知道为什么又问了一次。 */
    private fun summarizeFor(
        name: String,
        args: Map<String, Any?>,
        envChange: Boolean,
        guardedUi: Boolean,
    ): String {
        val summary = ToolPolicy.summarize(name, args)
        return when {
            guardedUi -> "敏感界面：$summary"
            envChange -> "环境变更：$summary"
            else -> summary
        }
    }

    /** 界面动作真正落到哪个包：有 ref 看节点，没有就看当前前台应用。 */
    private fun uiTargetPackage(name: String, args: Map<String, Any?>): String? = when (name) {
        "ui_act" -> (args["ref"] as? String)?.let { ref ->
            runCatching {
                AccessibilityBridge.withLock { AccessibilityBridge.node(ref)?.packageName?.toString() }
            }.getOrNull()
        } ?: AccessibilityBridge.state.value.packageName
        "ui_wait" -> (args["package"] as? String) ?: AccessibilityBridge.state.value.packageName
        else -> null
    }

    /** 安装器、权限弹窗、系统设置/界面：模型不能在没有确认的情况下操作。 */
    private fun uiTargetGuarded(name: String, args: Map<String, Any?>): Boolean {
        if (name != "ui_act" && name != "ui_wait") return false
        val pkg = uiTargetPackage(name, args) ?: return false
        return BLOCKED_UI_PACKAGES.any { pkg == it || pkg.startsWith("$it.") }
    }

    /** 界面调试工具是否被用户在设置里单独放行（`auto_approve`）。 */
    private fun uiToolExempt(name: String): Boolean {
        if (!isUiTool(name)) return false
        return AppStorage.prefs(context, "accessibility").getBoolean("auto_approve", false)
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
            // 先只读尺寸，再按 2 的幂降采样解码；避免超大图整幅进内存。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                return Outcome("not a decodable image: ${file.path}", false)
            }
            var sample = 1
            while (maxOf(width, height) / (sample * 2) >= TARGET_IMAGE_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.path, options)
                ?: return Outcome("not a decodable image: ${file.path}", false)
            val scale = minOf(1f, TARGET_IMAGE_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it !== bitmap) bitmap.recycle() }
            } else {
                bitmap
            }
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            Outcome(
                "图片已读取并附在下一段上下文里：${file.path}（${width}x${height}）",
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
        if (referencesConfigRoot(code) || referencesConfigRoot(script?.absolutePath.orEmpty())) {
            return outsideWorkspace(script?.absolutePath ?: "code")
        }
        val filename = script?.absolutePath ?: "<wbhub>"
        val argv = (args["args"] as? List<*>)
            ?.map { it?.toString().orEmpty() }
            .orEmpty()
        val argvJson = JSONArray(argv).toString()
        // 私有工具目录：Python 包只从这里加载。
        val sysPathJson = JSONArray().apply {
            put(File(toolRoot, "py_modules").absolutePath)
            put(File(toolRoot, "site-packages").absolutePath)
        }.toString()
        val timeout = (args.int("timeout_seconds") ?: 60).coerceIn(1, 300)
        if (!Python.isStarted()) {
            synchronized(PYTHON_LOCK) {
                if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
            }
        }
        // CPython 解释器是进程级单例，且 os.chdir / sys.path / sys.argv 都是全局状态。
        // 并发调用会互相串改，所以统一排到一条线程上顺序执行。
        val worker = PYTHON_EXECUTOR.submit<String> {
            Python.getInstance()
                .getModule("wbhub_coderunner")
                .callAttr("run", code, workDir.absolutePath, filename, argvJson, sysPathJson)
                .toString()
        }
        val output = try {
            worker.get(timeout.toLong(), TimeUnit.SECONDS)
        } catch (timeoutError: java.util.concurrent.TimeoutException) {
            worker.cancel(true)
            return Outcome("python 超时（${timeout}s），代码仍在后台运行", false)
        } catch (error: java.util.concurrent.ExecutionException) {
            val cause = error.cause ?: error
            Log.e(TAG, "python failed", cause)
            return Outcome("python 启动或执行失败：${cause.message}", false)
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
            resolveWritable(rawPath) ?: return outsideWorkspace(rawPath)
        }
        val canonical = target.canonicalFile
        val allowed = if (inTools) {
            canonical == toolRoot.canonicalFile ||
                canonical.path.startsWith(toolRoot.canonicalFile.path + File.separator)
        } else {
            insideWorkDir(canonical)
        }
        if (!allowed) {
            return outsideWorkspace(rawPath)
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
                    connection.inputStream.use { input ->
                        tmp.outputStream().use { out ->
                            when (copyLimited(input, out)) {
                                -1L -> { tmp.delete(); return Outcome("(stopped)", false) }
                                -2L -> { tmp.delete(); return Outcome("下载超过 512MB 上限", false) }
                            }
                        }
                    }
                    val count = runCatching { unzipInto(tmp, canonical) }.getOrElse {
                        tmp.delete()
                        return Outcome("解压失败：${it.message}", false)
                    }
                    tmp.delete()
                    Outcome("已解压 $count 个文件到 ${canonical.path}", true)
                } else {
                    canonical.parentFile?.mkdirs()
                    val copied = connection.inputStream.use { input ->
                        canonical.outputStream().use { out -> copyLimited(input, out) }
                    }
                    if (copied < 0) {
                        canonical.delete()
                        return if (copied == -1L) {
                            Outcome("(stopped)", false)
                        } else {
                            Outcome("下载超过 512MB 上限", false)
                        }
                    }
                    canonical.setExecutable(true, false)
                    Outcome("已下载 ${canonical.path}（${canonical.length()} 字节）", true)
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { Outcome("download failed: ${it.message}", false) }
    }

    /** 带取消与大小上限的流拷贝；返回 -1 表示已停止，-2 表示超过上限。 */
    private fun copyLimited(input: java.io.InputStream, out: java.io.OutputStream): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (cancelledCheck()) return -1
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_DOWNLOAD_BYTES) return -2
            out.write(buffer, 0, n)
        }
        return total
    }

    /** 解压 zip，拒绝把条目写到目标目录之外，并限制条目数与展开体积。 */
    private fun unzipInto(archive: File, destDir: File): Int {
        val root = destDir.canonicalFile
        var count = 0
        var entries = 0
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zin ->
            while (true) {
                if (cancelledCheck()) throw IllegalStateException("已停止")
                val entry = zin.nextEntry ?: break
                if (++entries > MAX_ZIP_ENTRIES) throw IllegalStateException("条目数超过上限")
                val out = File(destDir, entry.name).canonicalFile
                if (out != root && !out.path.startsWith(root.path + File.separator)) {
                    zin.closeEntry()
                    continue
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { stream ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelledCheck()) throw IllegalStateException("已停止")
                            val n = zin.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_UNZIP_BYTES) {
                                throw IllegalStateException("解压后超过 1GB 上限")
                            }
                            stream.write(buffer, 0, n)
                        }
                    }
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
        if (referencesConfigRoot(command) && WRITE_INTENT.containsMatchIn(command)) {
            return outsideWorkspace("/sdcard/WBHub")
        }
        val timeout = (args.int("timeout_seconds") ?: DEFAULT_TIMEOUT_SECONDS)
            .coerceIn(1, MAX_TIMEOUT_SECONDS)
        return runCatching {
            // 用 setsid 让命令成为独立会话首进程，并把 pid 写成进程组号。
            // 否则结束 su 之后，su 拉起的 sh/diff 会变成孤儿继续跑。
            val runDir = File(toolRoot, "run").apply { mkdirs() }
            val token = "bash-${System.currentTimeMillis()}-${(1000..9999).random()}"
            val script = File(runDir, "$token.sh")
            val pgidFile = File(runDir, "$token.pgid")
            script.writeText("echo \$\$ > \"\$WB_PGID\"\nexec sh -c \"\$WB_CMD\"\n")
            val launch = "exec /system/bin/setsid /system/bin/sh ${script.absolutePath}"
            val argv = if (useRoot) {
                arrayOf("su", "-c", launch)
            } else {
                arrayOf("/system/bin/setsid", "/system/bin/sh", script.absolutePath)
            }

            fun killTree() {
                // 脚本可能还没来得及写 pgid：最多等 500ms，拿不到就只能杀直接子进程。
                var pgid: Int? = null
                repeat(10) {
                    if (pgid == null) {
                        pgid = runCatching { pgidFile.readText().trim().toInt() }.getOrNull()
                        if (pgid == null) Thread.sleep(50)
                    }
                }
                if (pgid != null && pgid > 1) {
                    val cmd = "kill -9 -$pgid 2>/dev/null; kill -9 $pgid 2>/dev/null"
                    val killer = if (useRoot) {
                        ProcessBuilder("su", "-c", cmd)
                    } else {
                        ProcessBuilder("sh", "-c", cmd)
                    }
                    runCatching {
                        killer.start().waitFor(500, TimeUnit.MILLISECONDS)
                    }
                }
            }

            try {
                // 只把私有工具目录的 bin 放进 PATH。
                val binDirs = File(toolRoot, "bin").absolutePath
                val tempDir = File(workDir, ".tmp").apply { mkdirs() }
                val basePath = System.getenv("PATH").orEmpty()
                val process = ProcessBuilder(*argv)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["PATH"] =
                            if (basePath.isBlank()) binDirs else "$binDirs:$basePath"
                        environment()["HOME"] = workDir.absolutePath
                        environment()["TMPDIR"] = tempDir.absolutePath
                        environment()["WB_CMD"] = command
                        environment()["WB_PGID"] = pgidFile.absolutePath
                    }
                    .start()
                // 输出必须边跑边读：先 readText() 会一直堵到进程退出，超时参数也就失效了。
                val output = StringBuilder()
                var truncated = false
                val reader = Thread({
                    runCatching {
                        var lastEmit = 0L
                        process.inputStream.bufferedReader().use { stream ->
                            val buffer = CharArray(8192)
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                val room = OUTPUT_LIMIT - output.length
                                if (room > 0) {
                                    output.append(buffer, 0, minOf(n, room))
                                    if (n > room) truncated = true
                                } else {
                                    truncated = true
                                }
                                // 命令有输出时把最后一行报上去，比单纯的心跳更有用。
                                val now = android.os.SystemClock.elapsedRealtime()
                                if (now - lastEmit >= 1_500) {
                                    lastEmit = now
                                    val line = output.lineSequence().lastOrNull { it.isNotBlank() }
                                    if (line != null) runCatching { progressSink(line.trim().take(80)) }
                                }
                            }
                        }
                    }
                }, "wbhub-bash-reader").apply { isDaemon = true; start() }

                // 200ms 一轮：停止键要在命令还在跑的时候就能生效，而不是等它结束。
                val deadline = android.os.SystemClock.elapsedRealtime() + timeout * 1000L
                while (true) {
                    if (process.waitFor(200, TimeUnit.MILLISECONDS)) break
                    if (cancelledCheck()) {
                        killTree()
                        process.destroyForcibly()
                        reader.join(300)
                        return@runCatching Outcome("(stopped)\n$output", false)
                    }
                    if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                        killTree()
                        process.destroyForcibly()
                        reader.join(500)
                        return@runCatching Outcome("(timed out after ${timeout}s)\n$output", false)
                    }
                }
                reader.join(1_000)
                val code = process.exitValue()
                val header = "exit code: $code"
                val suffix = if (truncated) "\n… output truncated" else ""
                Outcome("$header\n$output$suffix", code == 0)
            } finally {
                script.delete()
                pgidFile.delete()
            }
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

    /** 写操作只允许落在工作目录内，配置目录与会话归档一律拒绝。 */
    private fun resolveWritable(path: String?): File? {
        val file = resolve(path) ?: return null
        return if (insideWorkDir(file)) file.canonicalFile else null
    }

    private fun insideWorkDir(file: File): Boolean {
        val root = workDir.canonicalFile
        val target = file.canonicalFile
        return target == root || target.path.startsWith(root.path + File.separator)
    }

    private fun outsideWorkspace(path: String?): Outcome = Outcome(
        buildString {
            append("[E_WORKSPACE] 任务产物只能写在工作目录 ${workDir.absolutePath} 内（可以新建子目录）。")
            append("settings/records/database/agent/tools 等配置目录不存放任务文件，请改用相对路径。")
            if (!path.isNullOrBlank()) append(" 请求路径：$path")
        },
        false,
    )

    /**
     * shell 与 Python 无法在系统层面沙箱，只能做文本预检：
     * 工作区路径先替换掉，剩下仍引用配置根目录的命令直接拒绝。
     */
    private fun referencesConfigRoot(text: String): Boolean {
        if (text.isBlank()) return false
        var probe = text
        val workspace = AppStorage.workspace(context).absolutePath
        listOf(
            workDir.absolutePath,
            workspace,
            "/sdcard/WBHub/workspace",
            "/storage/emulated/0/WBHub/workspace",
        )
            .distinct()
            .sortedByDescending { it.length }
            .forEach { probe = probe.replace(it, "<workspace>") }
        val roots = buildList {
            add(AppStorage.root(context).absolutePath)
            add(File(Environment.getExternalStorageDirectory(), "WBHub").absolutePath)
            add(File("/sdcard", "WBHub").absolutePath)
            add(File(context.filesDir, "WBHub").absolutePath)
            add("/files/WBHub")
        }.distinct()
        if (roots.any { probe.contains(it) }) return true
        return RELATIVE_CONFIG.containsMatchIn(text)
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

        /** read 的内存上限：单行与整次输出各自封顶。 */
        private const val MAX_LINE_CHARS = 4_000
        private const val MAX_READ_CHARS = 120_000

        /** 图片交给模型前的最长边。 */
        private const val TARGET_IMAGE_SIDE = 1568

        /** 下载与解包的硬上限，挡住误操作和 zip bomb。 */
        private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024
        private const val MAX_UNZIP_BYTES = 1024L * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 20_000

        /** Python.start 只能成功一次，并发调用要串行。 */
        private val PYTHON_LOCK = Any()

        /** 解释器全局状态（cwd / sys.path / argv）不是线程安全的，执行也串行。 */
        private val PYTHON_EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wbhub-python").apply { isDaemon = true }
        }

        /** 工作区里的扩展目录，只建在私有工具目录，不落进 workspace。 */
        private val EXTENSION_DIRS = listOf("bin", "py_modules", "site-packages", "downloads")

        /** 相对路径向上跳出工作区、直指配置子目录的写法。 */
        private val RELATIVE_CONFIG = Regex(
            """(\.\./)+(settings|records|database|agent|tools|bin|py_modules|""" +
                """site-packages|libs|memory|secrets|signing|apktool|ipc)\b""",
            RegexOption.IGNORE_CASE,
        )

        /** shell 里可能改动文件的动作；只用来判断“引用配置路径”是否构成写操作。 */
        private val WRITE_INTENT = Regex(
            """>|\b(cp|mv|install|ln|mkdir|touch|rm|rmdir|dd|tee|chmod|chown|truncate)\b|""" +
                """\bsed\s+-i|\bperl\s+-[a-z]*i""",
            RegexOption.IGNORE_CASE,
        )

        /** 只有这五个工具依赖主进程里的无障碍服务，其余全部在 :tools 进程执行。 */
        private val UI_TOOLS = setOf("ui_status", "ui_dump", "ui_act", "ui_wait", "screenshot")
        private val MEMORY_TOOLS = setOf(
            "memory_save", "memory_update", "memory_search", "memory_list", "memory_forget",
        )

        /** 这些界面即使开了「不再弹窗」也必须先确认：安装、授权、系统设置。 */
        private val BLOCKED_UI_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.settings",
            "com.android.systemui",
            "com.android.vending",
            "com.google.android.gms",
            "com.miui.packageinstaller",
            "com.samsung.android.packageinstaller",
        )

        /** 任务产物统一落在 `/sdcard/WBHub/workspace`，配置目录保持干净。 */
        fun defaultWorkDir(): File =
            File(File(Environment.getExternalStorageDirectory(), "WBHub"), AppStorage.DIR_WORKSPACE)

        /** 把历史设置或模型传入的工作目录夹回 workspace；越界就退回默认值。 */
        fun sanitizeWorkDir(context: Context, requested: String?): File {
            val workspace = AppStorage.workspace(context).canonicalFile
            val candidate = requested
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { File(it).canonicalFile }.getOrNull() }
            val inside = candidate != null &&
                (candidate == workspace || candidate.path.startsWith(workspace.path + File.separator))
            return if (inside) candidate!! else workspace
        }

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
