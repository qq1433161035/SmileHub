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

    fun spec(name: String): ToolSpec? {
        refreshExternal()
        return registry.find(name)?.spec
    }

    fun execute(
        name: String,
        args: Map<String, Any?>,
        sessionId: String?,
        approval: String,
    ): ToolResult {
        refreshExternal()
        val tool = registry.find(name) ?: return ToolResult.fail("E_INPUT", "unknown tool: $name")
        val projectId = (args["project_id"] as? String)?.takeIf { it.isNotBlank() }
        val operationId = db.startOperation(
            projectId = projectId,
            sessionId = sessionId,
            tool = name,
            argsJson = JSONObject(args).toString(),
            risk = tool.spec.risk.name,
            approval = approval,
        )

        val executor = Executors.newSingleThreadExecutor()
        val result = try {
            val future = executor.submit<ToolResult> { tool.run(args) }
            future.get(tool.spec.timeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (timeout: TimeoutException) {
            ToolResult.fail("E_TIMEOUT", "$name 超过 ${tool.spec.timeoutSeconds}s 未完成")
        } catch (error: Exception) {
            val cause = error.cause ?: error
            ToolResult.error("E_INPUT", "$name 执行失败：${cause.message}", cause.stackTraceToString().take(2_000))
        } finally {
            executor.shutdownNow()
        }

        val log = workspace.logFile(operationId)
        runCatching {
            log.parentFile?.mkdirs()
            log.writeText(result.render())
        }
        db.finishOperation(
            id = operationId,
            status = when {
                result.ok -> "OK"
                result.summary.contains("[E_TIMEOUT]") -> "TIMEOUT"
                else -> "FAILED"
            },
            exitCode = if (result.ok) 0 else 1,
            summary = result.summary.take(500),
            outputRef = workspace.display(log),
        )
        db.insertDiagnostics(operationId, result.diagnostics)
        return result
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 1_000L
    }
}
