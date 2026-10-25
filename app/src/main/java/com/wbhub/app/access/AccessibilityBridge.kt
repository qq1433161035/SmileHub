package com.wbhub.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无障碍服务的进程内入口。
 *
 * 与 [com.wbhub.app.tools.adb.AdbSession] 同构：系统服务连上时登记，断开时清空，
 * 工具层只读这里的状态，不直接持有 Service。
 *
 * 节点快照缓存在这里：`ui_dump` 产出的 `ref` 必须能在随后的 `ui_act` 里解析，
 * 但引用只在一次快照内有效，所以缓存同时记录时间，超过 [SNAPSHOT_TTL_MS] 一律作废。
 */
object AccessibilityBridge {

    /** 快照有效期：超过后 ref 不再可信，必须重新 dump。 */
    const val SNAPSHOT_TTL_MS = 10_000L

    data class State(
        val enabled: Boolean,
        val connected: Boolean,
        val packageName: String?,
        val message: String,
    )

    private val _state = MutableStateFlow(State(false, false, null, "未开启，点击前往系统设置"))
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()

    @Volatile
    private var service: AgentAccessibilityService? = null

    @Volatile
    private var lastSnapshot: NodeSnapshot? = null

    /** 最近一次界面内容变化的时间（uptime），`ui_wait idle` 用。 */
    @Volatile
    private var lastChangeAt: Long = 0L

    fun attach(target: AgentAccessibilityService) {
        service = target
        lastChangeAt = SystemClock.uptimeMillis()
        android.util.Log.i("WBHub", "accessibility attached")
        publish(
            State(
                enabled = true,
                connected = true,
                packageName = target.rootInActiveWindow?.packageName?.toString(),
                message = "已连接",
            ),
        )
    }

    fun detach(target: AgentAccessibilityService) {
        if (service !== target) return
        service = null
        lastSnapshot?.release()
        lastSnapshot = null
        android.util.Log.i("WBHub", "accessibility detached")
        publish(State(enabled = isEnabled(target), connected = false, packageName = null, message = "已授权，等待系统连接"))
    }

    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> lastChangeAt = SystemClock.uptimeMillis()
            else -> Unit
        }
        // 窗口切换/新窗口出现后，旧快照的 ref 可能指向后台窗口，必须失效。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            invalidateSnapshot()
        }
        val pkg = event.packageName?.toString()?.takeIf { it.isNotBlank() } ?: return
        val current = _state.value
        if (current.packageName != pkg) {
            invalidateSnapshot()
            publish(current.copy(enabled = true, connected = service != null, packageName = pkg, message = describe(pkg)))
        }
    }

    /** 让最近一次快照立即失效；`ui_act` 会因此返回 `E_STALE_REF`。 */
    private fun invalidateSnapshot() {
        withLock {
            val old = lastSnapshot ?: return@withLock
            lastSnapshot = null
            runCatching { old.release() }
        }
    }

    /** 页面回到前台时调用：系统状态可能在应用之外被改过。 */
    fun refresh(context: Context) {
        val enabled = isEnabled(context)
        val current = _state.value
        val pkg = current.packageName
        android.util.Log.i("WBHub", "accessibility refresh enabled=$enabled connected=${service != null}")
        publish(
            State(
                enabled = enabled,
                connected = service != null,
                packageName = pkg,
                message = when {
                    service != null -> describe(pkg)
                    enabled -> "已授权，等待系统连接"
                    else -> "未开启，点击前往系统设置"
                },
            ),
        )
    }

    fun service(): AgentAccessibilityService? = service

    fun isConnected(): Boolean = service != null

    fun isEnabled(context: Context): Boolean {
        val expected = android.content.ComponentName(context, AgentAccessibilityService::class.java)
        val viaManager = runCatching {
            val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                val name = serviceInfo.name.orEmpty()
                val component = if (name.startsWith(".")) {
                    android.content.ComponentName(serviceInfo.packageName, serviceInfo.packageName + name)
                } else {
                    android.content.ComponentName(serviceInfo.packageName, name)
                }
                component == expected
            }
        }.getOrDefault(false)
        if (viaManager) return true
        // 部分 ROM 的 getEnabledAccessibilityServiceList 只返回已绑定的服务；
        // 直接读安全设置更接近系统真实开关，也便于用户手动改完后立刻生效。
        return runCatching {
            val raw = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            raw.split(':').any { entry ->
                val component = android.content.ComponentName.unflattenFromString(entry)
                component != null &&
                    component.packageName == expected.packageName &&
                    component.className == expected.className
            }
        }.getOrDefault(false)
    }

    fun lastChangeAt(): Long = lastChangeAt

    /** 读取最近一次快照；过期或不存在时返回 null。 */
    fun snapshot(): NodeSnapshot? {
        val snapshot = lastSnapshot ?: return null
        if (SystemClock.uptimeMillis() - snapshot.at > SNAPSHOT_TTL_MS) {
            snapshot.release()
            lastSnapshot = null
            return null
        }
        return snapshot
    }

    fun remember(snapshot: NodeSnapshot) {
        val previous = lastSnapshot
        lastSnapshot = snapshot
        if (previous !== snapshot) previous?.release()
    }

    /** 串行执行遍历与动作：AccessibilityNodeInfo 不是线程安全的。 */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    fun node(ref: String?): AccessibilityNodeInfo? {
        if (ref.isNullOrBlank()) return null
        return snapshot()?.handles?.get(ref)
    }

    private fun describe(pkg: String?): String = if (pkg.isNullOrBlank()) "已连接" else "已连接：$pkg"

    private fun publish(next: State) {
        if (_state.value != next) {
            android.util.Log.i("WBHub", "accessibility state -> $next")
            _state.value = next
        }
    }
}
