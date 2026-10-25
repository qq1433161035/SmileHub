package com.wbhub.app.tools.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.tools.IToolCallback
import com.wbhub.app.tools.IToolWorker
import com.wbhub.app.tools.ToolingRuntime
import com.wbhub.app.tools.adb.AdbSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 工具执行进程（`:tools`）。
 *
 * 除了无障碍相关的五个工具，其余工具都在这里跑：内置的文件/命令/Python，
 * 以及 APK、DEX、SO、ADB 这些引擎工具。好处是工具里的 native 崩溃不会带走
 * 界面，停止时也可以整进程回收。
 *
 * 参数与结果都可能超过 Binder 的 1MB 事务上限，超过部分落到私有目录的
 * `WBHub/ipc` 里，由主进程读完再调 [release] 删除。
 */
class ToolWorkerService : Service() {

    private val calls = ConcurrentHashMap<String, AtomicBoolean>()

    /** 停止指令可能先于 call() 到达，先记下来，等调用进来时直接置为已取消。 */
    private val cancelledIds = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val ipcDir: File by lazy {
        File(filesDir, "WBHub/ipc").apply { mkdirs() }
    }

    /** 只有本进程建立 ADB 会话，主进程的设置页通过 AIDL 读状态。 */
    private val runtime: ToolingRuntime by lazy {
        ToolingRuntime.get(this, connectAdb = true)
    }

    override fun onCreate() {
        super.onCreate()
        // 预热：注册外部工具、按设置自动连接无线调试。放到后台，避免拖慢绑定。
        Thread({
            runCatching { runtime }
        }, "wbhub-tools-warmup").apply {
            isDaemon = true
            start()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IToolWorker.Stub() {

        override fun specs(): String = runCatching {
            val array = JSONArray()
            runtime.executor.specs().forEach { spec ->
                array.put(
                    JSONObject().apply {
                        put("name", spec.name)
                        put("risk", spec.risk.name)
                        put("timeoutSeconds", spec.timeoutSeconds)
                        put("definition", JSONObject(spec.definition() as Map<*, *>))
                    },
                )
            }
            array.toString()
        }.getOrElse { "[]" }

        override fun call(
            callId: String,
            name: String,
            argsJson: String,
            argsRef: String,
            sessionId: String,
            approval: String,
            workDir: String,
            useRoot: Boolean,
            callback: IToolCallback?,
        ): String {
            val cancelled = AtomicBoolean(cancelledIds.remove(callId))
            calls[callId] = cancelled
            val progress: (String) -> Unit = { message ->
                runCatching { callback?.onProgress(callId, message.take(120)) }
            }
            val outcome = try {
                val args = jsonToMap(
                    if (argsRef.isNotBlank()) File(argsRef).readText() else argsJson,
                )
                execute(
                    name = name,
                    args = args,
                    sessionId = sessionId,
                    approval = approval,
                    workDir = AgentTools.sanitizeWorkDir(this@ToolWorkerService, workDir),
                    useRoot = useRoot,
                    onProgress = progress,
                    isCancelled = { cancelled.get() },
                )
            } catch (error: Throwable) {
                Envelope(false, "$name 执行失败：${error.message}", null)
            } finally {
                calls.remove(callId)
                if (argsRef.isNotBlank()) runCatching { File(argsRef).delete() }
            }
            return envelope(callId, outcome)
        }

        override fun cancel(callId: String) {
            val flag = calls[callId]
            if (flag != null) {
                flag.set(true)
            } else {
                // call() 还没进来：先记住，避免停止指令丢失。
                if (cancelledIds.size > 128) cancelledIds.clear()
                cancelledIds.add(callId)
            }
        }

        override fun release(callId: String) {
            cancelledIds.remove(callId)
            ipcDir.listFiles()
                ?.filter { it.name.startsWith("$callId.") }
                ?.forEach { runCatching { it.delete() } }
        }

        override fun adbState(): String {
            // 实时查询：state 只是最后一次动作的快照，换网/休眠后会失真。
            val state = AdbSession.currentState()
            return JSONObject().apply {
                put("connected", state.connected)
                put("endpoint", state.endpoint.orEmpty())
                put("message", state.message)
            }.toString()
        }

        override fun adbPair(code: String): String =
            adbResult(AdbSession.pair(this@ToolWorkerService, code))

        override fun adbConnect(): String =
            adbResult(AdbSession.connect(this@ToolWorkerService))

        override fun adbResetKeys() {
            runCatching { AdbSession.resetKeys(this@ToolWorkerService) }
        }
    }

    private data class Envelope(val ok: Boolean, val text: String, val image: String?)

    private fun execute(
        name: String,
        args: Map<String, Any?>,
        sessionId: String,
        approval: String,
        workDir: File,
        useRoot: Boolean,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): Envelope {
        if (name in BUILTIN_TOOLS) {
            // 每次调用新造一个实例：进度出口与停止标志是实例字段，并发调用不能共用。
            val tools = AgentTools(
                context = this,
                workDir = workDir,
                useRoot = useRoot,
                tooling = null,
                sessionId = sessionId.ifBlank { null },
            )
            val outcome = tools.run(
                name = name,
                args = args,
                approval = ApprovalMode.ALLOW_ALL,
                ask = { true },
                isCancelled = isCancelled,
                onProgress = onProgress,
            )
            return Envelope(outcome.ok, outcome.text, outcome.image)
        }
        val result = runtime.executor.execute(
            name = name,
            args = args,
            sessionId = sessionId.ifBlank { null },
            approval = approval,
            onProgress = onProgress,
            isCancelled = isCancelled,
        )
        return Envelope(result.ok, result.render(), null)
    }

    private fun envelope(callId: String, outcome: Envelope): String {
        val json = JSONObject().put("ok", outcome.ok)
        if (outcome.text.length > INLINE_LIMIT) {
            json.put("textFile", spill(callId, "txt", outcome.text))
        } else {
            json.put("text", outcome.text)
        }
        outcome.image?.let { image ->
            if (image.length > INLINE_LIMIT) {
                json.put("imageFile", spill(callId, "img", image))
            } else {
                json.put("image", image)
            }
        }
        return json.toString()
    }

    private fun spill(callId: String, suffix: String, text: String): String {
        val file = File(ipcDir, "$callId.$suffix")
        file.writeText(text)
        return file.absolutePath
    }

    private fun adbResult(result: AdbSession.Result): String = JSONObject().apply {
        put("ok", result.ok)
        put("text", result.text)
    }.toString()

    private companion object {
        /** 与 AgentTools 的分支保持一致：这些名字由内置实现处理，不进引擎表。 */
        val BUILTIN_TOOLS = setOf("read", "write", "edit", "view_image", "python", "download", "bash")

        /** Binder 单次事务的安全上限（1MB 的一半，留出 JSON 逃逸的余量）。 */
        const val INLINE_LIMIT = 200_000
    }
}
