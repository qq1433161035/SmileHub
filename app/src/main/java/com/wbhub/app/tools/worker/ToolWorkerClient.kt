package com.wbhub.app.tools.worker

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.wbhub.app.tools.IToolCallback
import com.wbhub.app.tools.IToolWorker
import com.wbhub.app.tools.ToolRisk
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 主进程侧的 :tools 代理。
 *
 * 绑定、清单缓存、参数/结果分片和取消都在这里；[com.wbhub.app.agent.AgentTools]
 * 只看到一个普通的执行入口。工具进程不可用时返回 [CODE_UNAVAILABLE]，
 * 由调用方决定是否退回本进程执行。
 */
class ToolWorkerClient private constructor(private val context: Context) {

    /** 一次工具调用的结果；text 即模型要读到的完整文本。 */
    data class RemoteOutcome(val ok: Boolean, val text: String, val image: String? = null)

    data class AdbUiState(val connected: Boolean, val endpoint: String?, val message: String)

    data class AdbResult(val ok: Boolean, val text: String)

    private data class Advert(
        val name: String,
        val risk: ToolRisk,
        val timeoutSeconds: Int,
        val definition: Map<String, Any>,
    )

    private val lock = java.util.concurrent.locks.ReentrantLock()
    private val ready = lock.newCondition()
    private val binding = AtomicBoolean(false)

    @Volatile
    private var worker: IToolWorker? = null

    @Volatile
    private var inventory: List<Advert> = emptyList()

    @Volatile
    private var specsLoaded = false

    private val ipcDir: File by lazy {
        File(context.filesDir, "WBHub/ipc").apply { mkdirs() }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val api = IToolWorker.Stub.asInterface(service)
            worker = api
            signal()
            // 清单要等工具进程把运行时建好，不能占用主线程的 Binder 回调。
            Thread({
                inventory = runCatching { parseSpecs(api.specs()) }.getOrDefault(emptyList())
                specsLoaded = true
                signal()
            }, "wbhub-tools-specs").apply {
                isDaemon = true
                start()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            reset()
            // 进程被杀后系统不一定再回调 onBindingDied；不清标记就永远不再重绑，
            // 所有工具会静默退化为主进程执行。
            binding.set(false)
        }

        override fun onBindingDied(name: ComponentName?) {
            reset()
            binding.set(false)
        }

        override fun onNullBinding(name: ComponentName?) {
            reset()
            binding.set(false)
        }
    }

    private fun signal() {
        lock.lock()
        try {
            ready.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun reset() {
        worker = null
        inventory = emptyList()
        specsLoaded = false
        signal()
    }

    /** 提前绑定，避免第一次工具调用还要等进程启动。 */
    fun warmUp() {
        runCatching { await(2_000) }
    }

    fun definitions(): List<Map<String, Any>> {
        val api = await(BIND_TIMEOUT_MS) ?: return emptyList()
        awaitSpecs(SPECS_TIMEOUT_MS)
        // 外部工具目录可能在运行中被改动：每次取清单时重新读一次，
        // 这样新增脚本不需要重启应用。
        runCatching {
            inventory = parseSpecs(api.specs())
            specsLoaded = true
        }
        return inventory.map { advert -> advert.definition }
    }

    fun risk(name: String): ToolRisk? {
        await(BIND_TIMEOUT_MS)
        awaitSpecs(SPECS_TIMEOUT_MS)
        return inventory.firstOrNull { it.name == name }?.risk
    }

    fun timeoutSeconds(name: String): Int? {
        await(BIND_TIMEOUT_MS)
        awaitSpecs(SPECS_TIMEOUT_MS)
        return inventory.firstOrNull { it.name == name }?.timeoutSeconds
    }

    fun execute(
        name: String,
        args: Map<String, Any?>,
        sessionId: String?,
        approval: String,
        workDir: File,
        useRoot: Boolean,
        onProgress: (String) -> Unit,
        isCancelled: () -> Boolean,
    ): RemoteOutcome {
        val api = await(BIND_TIMEOUT_MS)
            ?: return RemoteOutcome(false, "[$CODE_UNAVAILABLE] 工具进程未就绪")

        val callId = UUID.randomUUID().toString().replace("-", "")
        val argsJson = args.toJson()
        val argsRef = if (argsJson.length > INLINE_LIMIT) {
            File(ipcDir, "$callId.args").apply { writeText(argsJson) }.absolutePath
        } else {
            ""
        }

        val finished = AtomicBoolean(false)
        val callback = object : IToolCallback.Stub() {
            override fun onProgress(id: String, message: String) {
                if (id == callId) runCatching { onProgress(message) }
            }
        }
        // AIDL 调用本身是阻塞的，只能另起一条线程轮询停止标志。
        val watcher = Thread({
            while (!finished.get()) {
                if (isCancelled()) {
                    runCatching { api.cancel(callId) }
                    return@Thread
                }
                runCatching { Thread.sleep(CANCEL_POLL_MS) }
            }
        }, "wbhub-tool-cancel").apply {
            isDaemon = true
            start()
        }

        return try {
            val raw = api.call(
                callId,
                name,
                if (argsRef.isEmpty()) argsJson else "",
                argsRef,
                sessionId.orEmpty(),
                approval,
                workDir.absolutePath,
                useRoot,
                callback,
            )
            decode(raw)
        } catch (error: Throwable) {
            RemoteOutcome(false, "[$CODE_UNAVAILABLE] 工具进程调用失败：${error.message}")
        } finally {
            finished.set(true)
            watcher.interrupt()
            runCatching { api.release(callId) }
            if (argsRef.isNotEmpty()) runCatching { File(argsRef).delete() }
        }
    }

    fun adbState(): AdbUiState {
        val api = await(BIND_TIMEOUT_MS) ?: return AdbUiState(false, null, "工具进程未启动")
        return runCatching {
            val json = JSONObject(api.adbState())
            AdbUiState(
                connected = json.optBoolean("connected"),
                endpoint = json.optString("endpoint").takeIf { it.isNotBlank() },
                message = json.optString("message", "未连接"),
            )
        }.getOrElse { AdbUiState(false, null, "读取调试状态失败") }
    }

    fun adbPair(code: String): AdbResult {
        val api = await(BIND_TIMEOUT_MS) ?: return AdbResult(false, "工具进程未启动")
        return runCatching { adbResult(api.adbPair(code)) }
            .getOrElse { AdbResult(false, "配对失败：${it.message}") }
    }

    fun adbConnect(): AdbResult {
        val api = await(BIND_TIMEOUT_MS) ?: return AdbResult(false, "工具进程未启动")
        return runCatching { adbResult(api.adbConnect()) }
            .getOrElse { AdbResult(false, "连接失败：${it.message}") }
    }

    fun adbResetKeys() {
        val api = await(BIND_TIMEOUT_MS) ?: return
        runCatching { api.adbResetKeys() }
    }

    private fun adbResult(raw: String): AdbResult = runCatching {
        val json = JSONObject(raw)
        AdbResult(json.optBoolean("ok"), json.optString("text"))
    }.getOrElse { AdbResult(false, raw) }

    private fun decode(raw: String): RemoteOutcome {
        val json = JSONObject(raw)
        val text = json.optString("textFile").takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).readText() }.getOrDefault("[无法读取结果 ${path}]") }
            ?: json.optString("text")
        val image = json.optString("imageFile").takeIf { it.isNotBlank() }
            ?.let { path -> runCatching { File(path).readText() }.getOrNull() }
            ?: json.optString("image").takeIf { it.isNotBlank() }
        return RemoteOutcome(json.optBoolean("ok"), text, image)
    }

    private fun parseSpecs(raw: String): List<Advert> {
        val array = org.json.JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val definition = jsonToValue(item.optJSONObject("definition")) as? Map<*, *>
                ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            Advert(
                name = item.optString("name"),
                risk = runCatching { ToolRisk.valueOf(item.optString("risk")) }
                    .getOrDefault(ToolRisk.EXEC),
                timeoutSeconds = item.optInt("timeoutSeconds", 120),
                definition = definition.entries.associate { it.key.toString() to it.value } as Map<String, Any>,
            )
        }
    }

    private fun await(timeoutMs: Long): IToolWorker? {
        worker?.let { return it }
        bind()
        lock.lock()
        try {
            if (worker == null) ready.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            return worker
        } finally {
            lock.unlock()
        }
    }

    private fun awaitSpecs(timeoutMs: Long) {
        if (specsLoaded) return
        lock.lock()
        try {
            var remaining = timeoutMs
            while (!specsLoaded && remaining > 0) {
                val started = System.currentTimeMillis()
                ready.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                remaining -= System.currentTimeMillis() - started
            }
        } finally {
            lock.unlock()
        }
    }

    private fun bind() {
        if (worker != null) return
        if (!binding.compareAndSet(false, true)) return
        val bound = runCatching {
            context.bindService(
                Intent(context, ToolWorkerService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrDefault(false)
        if (!bound) {
            binding.set(false)
            signal()
        }
    }

    companion object {
        /** 工具进程不可用；调用方可以据此退回本进程执行。 */
        const val CODE_UNAVAILABLE = "E_WORKER"

        private const val BIND_TIMEOUT_MS = 3_000L
        private const val SPECS_TIMEOUT_MS = 5_000L
        private const val CANCEL_POLL_MS = 200L

        /** Binder 事务上限 1MB，留一半余量给 JSON 转义。 */
        private const val INLINE_LIMIT = 200_000

        @Volatile
        private var instance: ToolWorkerClient? = null

        fun get(context: Context): ToolWorkerClient =
            instance ?: synchronized(this) {
                instance ?: ToolWorkerClient(context.applicationContext).also { instance = it }
            }
    }
}
