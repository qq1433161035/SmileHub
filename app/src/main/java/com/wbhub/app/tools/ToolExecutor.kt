package com.wbhub.app.tools

import com.wbhub.app.tooling.ToolingDb
import com.wbhub.app.tooling.Workspace
import com.wbhub.app.tools.external.ExternalToolSource
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs registered tools with a timeout, an audit row, and an on-disk log.
 *
 * Approval is decided by the caller (it needs the UI); by the time a call
 * reaches this class it has been allowed. Every call still lands in
 * `operations`, including the ones that fail or time out.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val db: ToolingDb,
    private val workspace: Workspace,
    private val external: ExternalToolSource? = null,
) {

    @Volatile
    private var lastScan = 0L

    /**
     * Re-reads `/WBHub/tools` so an edited script applies without a restart.
     * At most once per second: the list is rebuilt on every prompt anyway, and
     * scanning on each individual call would add IO to hot paths.
     */
    private fun refreshExternal() {
        val source = external ?: return
        val now = System.currentTimeMillis()
        if (now - lastScan < SCAN_INTERVAL_MS) return
        synchronized(this) {
            if (System.currentTimeMillis() - lastScan < SCAN_INTERVAL_MS) return
            lastScan = System.currentTimeMillis()
            registry.replaceSource(ToolSource.EXTERNAL, source.load())
        }
    }

    fun definitions(): List<Map<String, Any>> {
        refreshExternal()
        return registry.definitions()
    }

    /** 全部工具的声明，供 :tools 进程对外发布清单。 */
    fun specs(): List<ToolSpec> {
        refreshExternal()
        return registry.specs()
    }

    fun spec(name: String): ToolSpec? {
        refreshExternal()
        return registry.find(name)?.spec
    }

    fun execute(
        name: String,
        args: Map<String, Any?>,
        sessionId: String?,
        approval: String,
        onProgress: (String) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): ToolResult {
        refreshExternal()
        val tool = registry.find(name) ?: return ToolResult.fail("E_INPUT", "unknown tool: $name")
        val projectId = (args["project_id"] as? String)?.takeIf { it.isNotBlank() }
        // 审计写库失败不能连累工具执行或把异常抛到 Agent 协程。
        val operationId = runCatching {
            db.startOperation(
                projectId = projectId,
                sessionId = sessionId,
                tool = name,
                argsJson = runCatching { JSONObject(args).toString() }.getOrDefault("{}"),
                risk = tool.spec.risk.name,
                approval = approval,
            )
        }.getOrElse {
            android.util.Log.w("WBHub", "startOperation failed", it)
            -1L
        }

        val executor = Executors.newSingleThreadExecutor()
        val startedAt = android.os.SystemClock.elapsedRealtime()
        // 心跳：反编译、反汇编这类调用没有中间输出，至少让界面与通知栏
        // 知道它还在跑，而不是看起来卡死。
        val beating = java.util.concurrent.atomic.AtomicBoolean(true)
        val heartbeat = Thread({
            var last = -1
            while (beating.get()) {
                try {
                    Thread.sleep(1_000)
                } catch (interrupted: InterruptedException) {
                    break
                }
                val elapsed = ((android.os.SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()
                if (elapsed >= 2 && elapsed != last) {
                    last = elapsed
                    runCatching { onProgress("已运行 ${elapsed}s") }
                }
            }
        }, "wbhub-tool-progress").apply { isDaemon = true; start() }
        val result = try {
            val future = executor.submit<ToolResult> { tool.run(args) }
            val deadline = startedAt + tool.spec.timeoutSeconds * 1000L
            var outcome: ToolResult? = null
            // 200ms 一轮，停止键不必等到超时才生效。
            while (outcome == null) {
                try {
                    outcome = future.get(200, TimeUnit.MILLISECONDS)
                } catch (slice: TimeoutException) {
                    when {
                        isCancelled() -> {
                            future.cancel(true)
                            outcome = ToolResult.fail("E_CANCELLED", "$name 已停止")
                        }
                        android.os.SystemClock.elapsedRealtime() >= deadline -> {
                            future.cancel(true)
                            outcome = ToolResult.fail(
                                "E_TIMEOUT",
                                "$name 超过 ${tool.spec.timeoutSeconds}s 未完成",
                            )
                        }
                    }
                } catch (error: java.util.concurrent.ExecutionException) {
                    val cause = error.cause ?: error
                    outcome = ToolResult.error(
                        "E_INPUT",
                        "$name 执行失败：${cause.message}",
                        cause.stackTraceToString().take(2_000),
                    )
                } catch (cancelled: java.util.concurrent.CancellationException) {
                    outcome = ToolResult.fail("E_CANCELLED", "$name 已停止")
                }
            }
            outcome ?: ToolResult.fail("E_INPUT", "$name 未返回结果")
        } finally {
            beating.set(false)
            heartbeat.interrupt()
            executor.shutdownNow()
        }

        // 注册表工具此前完全没有输出上限：ui_dump / adb_shell / logcat 把
        // 几十万字符直接塞给模型。落盘保留全文，回给模型的按 spec 截断。
        val capped = if (result.text.length > tool.spec.maxOutputBytes) {
            result.copy(
                text = result.text.take(tool.spec.maxOutputBytes) + "\n…[truncated]",
            )
        } else {
            result
        }

        if (operationId > 0) {
            val log = workspace.logFile(operationId)
            runCatching {
                log.parentFile?.mkdirs()
                log.writeText(result.render())
            }
            runCatching {
                db.finishOperation(
                    id = operationId,
                    status = when {
                        result.ok -> "OK"
                        result.summary.contains("[E_CANCELLED]") -> "CANCELLED"
                        result.summary.contains("[E_TIMEOUT]") -> "TIMEOUT"
                        else -> "FAILED"
                    },
                    exitCode = if (result.ok) 0 else 1,
                    summary = result.summary.take(500),
                    outputRef = workspace.display(log),
                )
                db.insertDiagnostics(operationId, result.diagnostics)
            }
        }
        return capped
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 1_000L
    }
}
