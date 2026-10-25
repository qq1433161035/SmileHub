package com.wbhub.app.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/** 语义动作优先，坐标手势兜底。所有方法都返回系统是否接受。 */
object UiActions {

    private const val ANCESTOR_LIMIT = 20

    fun click(node: AccessibilityNodeInfo?): Boolean {
        val target = clickable(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun longClick(node: AccessibilityNodeInfo?): Boolean {
        val target = clickable(node) ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
    }

    fun setText(node: AccessibilityNodeInfo?, text: String): Boolean {
        val target = editable(node) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun scroll(node: AccessibilityNodeInfo?, forward: Boolean): Boolean {
        val target = scrollable(node) ?: return false
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return target.performAction(action)
    }

    fun tap(service: AccessibilityService, x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(service, path, 0L, 60L)
    }

    fun swipe(
        service: AccessibilityService,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return gesture(service, path, 0L, durationMs.coerceIn(50L, 3_000L))
    }

    fun longPress(service: AccessibilityService, x: Float, y: Float, durationMs: Long = 600L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return gesture(service, path, 0L, durationMs.coerceIn(400L, 3_000L))
    }

    fun global(service: AccessibilityService, action: String): Boolean = when (action) {
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "home" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        "recents" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        "notifications" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    /** 节点本身不可点时，向上找最近的可点击祖先——列表项常是这种结构。 */
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < ANCESTOR_LIMIT) {
            if (current.isClickable) return current
            current = runCatching { current.parent }.getOrNull()
            depth++
        }
        return null
    }

    private fun scrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (current != null && depth < ANCESTOR_LIMIT) {
            if (current.isScrollable) return current
            current = runCatching { current.parent }.getOrNull()
            depth++
        }
        return null
    }

    /** 输入框可能是节点本身，也可能被包了一层，向下找一层。 */
    private fun editable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            if (child.isEditable) return child
        }
        return null
    }

    private fun gesture(
        service: AccessibilityService,
        path: Path,
        startTime: Long,
        durationMs: Long,
    ): Boolean = runCatching {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }.getOrDefault(false)
}
