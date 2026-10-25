package com.wbhub.app.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** 一个被保留下来的界面节点；[ref] 只在本次快照内有效。 */
data class UiNode(
    val ref: String,
    val depth: Int,
    val className: String,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checked: Boolean?,
    val enabled: Boolean,
    val packageName: String?,
)

/**
 * 一次界面快照：给模型看的文本，加上 `ref → 节点` 的句柄表。
 *
 * 句柄直接指向系统交来的 [AccessibilityNodeInfo]，窗口一变就可能失效；
 * 所以 `ui_act` 解析失败时要返回 `E_STALE_REF`，让模型重新 dump。
 */
class NodeSnapshot(
    val packageName: String?,
    val packageLabel: String?,
    val windowTitle: String?,
    val total: Int,
    val truncated: Boolean,
    val screen: String,
    val nodes: List<UiNode>,
    val handles: MutableMap<String, AccessibilityNodeInfo>,
    val at: Long = SystemClock.uptimeMillis(),
) {

    fun render(includeText: Boolean): String = buildString {
        append("窗口 ").append(packageName ?: "?")
        packageLabel?.takeIf { it.isNotBlank() && it != packageName }?.let { append("  ").append(it) }
        windowTitle?.takeIf { it.isNotBlank() }?.let { append("  ").append(it.take(60)) }
        append("  节点 ").append(nodes.size).append('/').append(total)
        if (truncated) append("（已裁剪）")
        append("  屏幕 ").append(screen)
        append('\n')

        var index = 0
        while (index < nodes.size) {
            val head = nodes[index]
            var end = index + 1
            while (end < nodes.size && sameShape(nodes[end], head)) end++
            val run = end - index
            val shown = if (run > COLLAPSE_LIMIT) COLLAPSE_LIMIT else run
            for (position in index until index + shown) appendNode(this, nodes[position], includeText)
            if (run > COLLAPSE_LIMIT) {
                append("… 另有 ").append(run - COLLAPSE_LIMIT).append(" 个同类节点\n")
            }
            index = end
        }
    }

    /**
     * 释放句柄。
     *
     * API 33 起 `recycle` 已废弃（系统自行管理），低版本则必须显式回收，
     * 否则每次 dump 都会在原生侧留下一批节点对象。
     */
    @Suppress("DEPRECATION")
    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
        handles.values.forEach { runCatching { it.recycle() } }
        handles.clear()
    }

    fun toJson(): String {
        val array = JSONArray()
        nodes.forEach { node ->
            array.put(
                JSONObject().apply {
                    put("ref", node.ref)
                    put("depth", node.depth)
                    put("class", node.className)
                    node.text?.let { put("text", it) }
                    node.desc?.let { put("desc", it) }
                    node.viewId?.let { put("id", it) }
                    put("bounds", JSONArray(listOf(node.bounds.left, node.bounds.top, node.bounds.right, node.bounds.bottom)))
                    put("clickable", node.clickable)
                    put("scrollable", node.scrollable)
                    put("editable", node.editable)
                    node.checked?.let { put("checked", it) }
                    put("enabled", node.enabled)
                },
            )
        }
        return JSONObject().apply {
            put("package", packageName ?: "")
            put("screen", screen)
            put("truncated", truncated)
            put("nodes", array)
        }.toString()
    }

    private fun appendNode(sink: StringBuilder, node: UiNode, includeText: Boolean) {
        sink.append(node.ref)
        repeat(node.depth.coerceAtMost(MAX_INDENT) * 2) { sink.append(' ') }
        sink.append(node.className)
        if (includeText) {
            node.text?.takeIf { it.isNotBlank() }?.let { sink.append(" \"").append(oneLine(it)).append('"') }
            node.desc?.takeIf { it.isNotBlank() }?.let { sink.append(" desc=\"").append(oneLine(it)).append('"') }
        }
        node.viewId?.takeIf { it.isNotBlank() }?.let { sink.append(" id=").append(it) }
        sink.append("  [").append(node.bounds.left).append(',').append(node.bounds.top)
            .append("][").append(node.bounds.right).append(',').append(node.bounds.bottom).append(']')
        val flags = buildList {
            if (node.clickable) add("clickable")
            if (node.scrollable) add("scrollable")
            if (node.editable) add("editable")
            node.checked?.let { add(if (it) "checked" else "unchecked") }
            if (!node.enabled) add("disabled")
        }
        if (flags.isNotEmpty()) sink.append("  ").append(flags.joinToString(" "))
        sink.append('\n')
    }

    private fun sameShape(a: UiNode, b: UiNode): Boolean =
        a.depth == b.depth && a.className == b.className &&
            a.text == b.text && a.desc == b.desc && a.viewId == b.viewId

    private fun oneLine(value: String): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_TEXT)

    private companion object {
        const val COLLAPSE_LIMIT = 30
        const val MAX_INDENT = 12
        const val MAX_TEXT = 80
    }
}

/** 把当前窗口压成一份可读快照；遍历与裁剪都在这里完成。 */
object UiSnapshotter {

    data class Filters(
        val packageName: String? = null,
        val clickableOnly: Boolean = false,
        val maxNodes: Int = 400,
    )

    private const val HARD_CAP = 3_000
    private const val MAX_DEPTH = 60

    /** 每次快照递增；ref 带世代号后，旧 ref 不会误解析到新快照的同序号节点。 */
    private val GENERATION = java.util.concurrent.atomic.AtomicLong(0)

    private class Raw(
        val node: AccessibilityNodeInfo,
        val depth: Int,
        val parent: Int,
        val interesting: Boolean,
        val clickable: Boolean,
    )

    fun capture(service: AccessibilityService, filters: Filters): NodeSnapshot? {
        val root = pickRoot(service, filters) ?: return null
        // root 可能在 walk 结束时被回收，先记下包名。
        val rootPackage = runCatching { root.packageName?.toString() }.getOrNull()
        val raw = ArrayList<Raw>(128)
        walk(root, 0, -1, raw)

        val total = raw.size
        val keep = BooleanArray(total)
        if (filters.clickableOnly) {
            raw.forEachIndexed { index, item ->
                if (item.clickable || item.node.isScrollable || item.node.isEditable) keep[index] = true
            }
        } else {
            raw.forEachIndexed { index, item -> if (item.interesting) keep[index] = true }
            // 结构上下文：把被保留节点的祖先链一起留下，缩进才有意义。
            raw.forEachIndexed { index, _ ->
                if (!keep[index]) return@forEachIndexed
                var parent = raw[index].parent
                while (parent >= 0 && !keep[parent]) {
                    keep[parent] = true
                    parent = raw[parent].parent
                }
            }
        }

        val selected = raw.indices.filter { keep[it] }
        val limited = if (selected.size <= filters.maxNodes) {
            selected
        } else {
            selected.sortedByDescending { raw[it].interesting }.take(filters.maxNodes).sorted()
        }

        val handles = LinkedHashMap<String, AccessibilityNodeInfo>()
        val nodes = ArrayList<UiNode>(limited.size)
        val generation = GENERATION.incrementAndGet()
        limited.forEachIndexed { position, rawIndex ->
            val item = raw[rawIndex]
            val node = item.node
            // 世代号前缀：上一份快照的 n12 与这一份的 n12 不再冲突。
            val ref = "n${generation}_${position + 1}"
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val checked = if (node.isCheckable) node.isChecked else null
            val viewId = node.viewIdResourceName?.substringAfterLast('/')
            nodes += UiNode(
                ref = ref,
                depth = item.depth,
                className = shortClassName(node.className?.toString()),
                text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                viewId = viewId,
                bounds = bounds,
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                editable = node.isEditable,
                checked = checked,
                enabled = node.isEnabled,
                packageName = node.packageName?.toString(),
            )
            handles[ref] = node
        }

        val metrics = service.resources.displayMetrics
        val windowTitle = runCatching {
            val windowId = root.windowId
            service.windows.orEmpty().firstOrNull { it.id == windowId }?.title?.toString()
        }.getOrNull()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // 未入选的节点不留在 handles 里，捕获结束就回收。
            val kept = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<AccessibilityNodeInfo, Boolean>(),
            )
            kept.addAll(handles.values)
            raw.forEach { item ->
                if (!kept.contains(item.node)) runCatching { item.node.recycle() }
            }
        }

        return NodeSnapshot(
            packageName = rootPackage,
            packageLabel = null,
            windowTitle = windowTitle,
            total = total,
            truncated = limited.size < selected.size || raw.size >= HARD_CAP,
            screen = "${metrics.widthPixels}x${metrics.heightPixels}",
            nodes = nodes,
            handles = handles,
        )
    }

    private fun pickRoot(service: AccessibilityService, filters: Filters): AccessibilityNodeInfo? {
        val wanted = filters.packageName?.takeIf { it.isNotBlank() }
        if (wanted != null) {
            val fromWindow = runCatching {
                service.windows.orEmpty()
                    .firstOrNull { window -> window.root?.packageName?.toString() == wanted }
                    ?.root
            }.getOrNull()
            if (fromWindow != null) return fromWindow
        }
        return runCatching { service.rootInActiveWindow }.getOrNull()
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int, parent: Int, sink: MutableList<Raw>) {
        if (sink.size >= HARD_CAP || depth > MAX_DEPTH) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val hasId = !node.viewIdResourceName.isNullOrEmpty()
        val clickable = node.isClickable
        val interesting = clickable || node.isScrollable || node.isEditable ||
            text.isNotEmpty() || desc.isNotEmpty() || hasId || node.isCheckable
        val bigEnough = bounds.width() > 1 && bounds.height() > 1
        val added = interesting || bigEnough
        if (added) {
            sink += Raw(node, depth, parent, interesting, clickable)
        }
        val self = if (added) sink.lastIndex else -1
        val childCount = runCatching { node.childCount }.getOrDefault(0)
        for (index in 0 until childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            walk(child, depth + 1, if (self >= 0 && sink[self].node === node) self else parent, sink)
        }
        // 没进快照的中间容器用完就回收；API 33+ 由系统管理。
        if (!added && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            runCatching { node.recycle() }
        }
    }

    private fun shortClassName(name: String?): String {
        if (name.isNullOrBlank()) return "Node"
        return when {
            name.startsWith("android.widget.") -> name.removePrefix("android.widget.")
            name.startsWith("android.view.") -> name.removePrefix("android.view.")
            name.startsWith("android.webkit.") -> name.removePrefix("android.webkit.")
            else -> name.substringAfterLast('.')
        }
    }
}
