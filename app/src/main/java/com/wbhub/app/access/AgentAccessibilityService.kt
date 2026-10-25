package com.wbhub.app.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * 系统绑定的无障碍服务，本身不做任何判断。
 *
 * 所有能力都在 [AccessibilityBridge] 与 `UiActions` 里，这样工具层不依赖
 * Service 生命周期，也不会在服务被系统回收后还留着引用。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityBridge.attach(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityBridge.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach(this)
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        AccessibilityBridge.onEvent(event)
    }

    override fun onInterrupt() = Unit
}
