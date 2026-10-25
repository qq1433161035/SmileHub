package com.wbhub.app.tools

import android.content.Context
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tooling.ToolingDb
import com.wbhub.app.tooling.Workspace
import com.wbhub.app.tools.adb.AdbSession
import com.wbhub.app.tools.engines.ApkEngine
import com.wbhub.app.tools.engines.SigningKeys
import com.wbhub.app.tools.engines.Toolchain
import com.wbhub.app.tools.external.ExternalToolSource

/**
 * Wires the tool stack once per process.
 *
 * The agent creates a tool list per prompt; sharing the database, workspace and
 * engines keeps SQLite handles and the signing key from being reopened on every
 * message.
 */
class ToolingRuntime private constructor(context: Context, connectAdb: Boolean = false) {

    val workspace = Workspace(context)
    val db = ToolingDb(context)
    val toolchain = Toolchain(context)
    val keys = SigningKeys(context)
    private val engine = ApkEngine(context, toolchain)
    private val external = ExternalToolSource(context)
    private val registry = ToolRegistry().apply {
        ToolingTools(context, workspace, db, engine, keys, toolchain).register(this)
        replaceSource(ToolSource.EXTERNAL, external.load())
    }
    val executor = ToolExecutor(registry, db, workspace, external)

    init {
        android.util.Log.i(
            "WBHub",
            "tools ready: ${registry.specs().size} total, ${registry.count(ToolSource.EXTERNAL)} external",
        )
        // 只有在设置里打开「启动时自动连接」才主动连 adb；配对过的密钥会直接放行，
        // 没配对则等用户在设置页输入配对码。ADB 会话只属于 :tools 进程，
        // 主进程构造这个运行时只是为了读数据库和跑无障碍工具。
        if (connectAdb) {
            Thread({
                runCatching {
                    val prefs = AppStorage.prefs(context, "adb")
                    if (!prefs.getBoolean("auto", false)) return@runCatching
                    AdbSession.connect(context)
                }
            }, "wbhub-adb-autoconnect").apply {
                isDaemon = true
                start()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ToolingRuntime? = null

        /**
         * @param connectAdb 是否在本进程建立无线调试会话。只有 :tools 进程应当传 true，
         *   否则两个进程会各自连一次，状态也会分叉。
         */
        fun get(context: Context, connectAdb: Boolean = false): ToolingRuntime =
            instance ?: synchronized(this) {
                instance ?: ToolingRuntime(context.applicationContext, connectAdb).also { instance = it }
            }
    }
}
