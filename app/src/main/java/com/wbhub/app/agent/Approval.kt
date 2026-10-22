package com.wbhub.app.agent

import com.wbhub.app.tools.ToolRisk

/**
 * How a tool call is authorised before it runs.
 *
 * The three modes trade attention against friction: reading a file and running
 * an arbitrary shell command are not the same risk, so a single "allow tools"
 * switch would either be useless or be clicked through without reading.
 */
enum class ApprovalMode(val label: String, val description: String) {
    /**
     * Writes and shell commands run without asking, reads never ask. The model
     * is trusted to stay inside what the prompt asked for.
     */
    ALLOW_ALL("允许所有", "不询问，直接执行"),

    /**
     * Read-only calls run freely; anything that writes or executes asks first.
     * The split follows what the call can undo rather than how it is spelled.
     */
    AUTO("自动判断", "读取自动放行，写入与命令需确认"),

    /** Every call asks, including reads. */
    ASK_EVERY("每次询问", "每次工具调用都需确认"),
    ;

    companion object {
        fun fromName(value: String?): ApprovalMode =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

/**
 * Classification of a tool call, used by [ApprovalMode.AUTO].
 *
 * A read is the only kind that changes nothing on disk and cannot run a
 * program, so it is the only kind auto-approved.
 */
object ToolPolicy {

    /** Whether the call only reads state and cannot change the device. */
    fun isReadOnly(name: String): Boolean = name == "read" || name == "view_image"

    /** Whether the call needs the user's answer under [mode]. */
    fun needsApproval(mode: ApprovalMode, name: String): Boolean = when (mode) {
        ApprovalMode.ALLOW_ALL -> false
        ApprovalMode.ASK_EVERY -> true
        ApprovalMode.AUTO -> !isReadOnly(name)
    }

    /** Same decision for a tool whose risk was declared with its spec. */
    fun needsApproval(mode: ApprovalMode, risk: ToolRisk): Boolean = when (mode) {
        ApprovalMode.ALLOW_ALL -> false
        ApprovalMode.ASK_EVERY -> true
        ApprovalMode.AUTO -> risk != ToolRisk.READ
    }

    /**
     * One-line description of what a call will do, shown in the prompt.
     *
     * The arguments decide the risk, so the summary names them rather than the
     * tool: "bash: rm -rf /sdcard" is readable, "bash" is not.
     */
    fun summarize(name: String, args: Map<String, Any?>): String = when (name) {
        "read" -> "读取 ${args["path"] ?: "?"}"
        "view_image" -> "查看图片 ${args["path"] ?: "?"}"
        "write" -> "写入 ${args["path"] ?: "?"}（${(args["content"] as? String)?.length ?: 0} 字符）"
        "edit" -> "编辑 ${args["path"] ?: "?"}"
        "bash" -> "执行命令：${args["command"] ?: "?"}"
        "download" -> "下载 ${args["url"] ?: "?"} → ${args["path"] ?: "?"}"
        "python" -> "执行 Python：${args["path"] ?: (args["code"] as? String)?.lineSequence()?.firstOrNull()?.take(40) ?: "?"}"
        "project_import" -> "导入 ${args["path"] ?: "?"}"
        "apk_decode" -> "解码 ${args["path"] ?: "?"}"
        "apk_build" -> "重打包 ${args["dir"] ?: "?"}"
        "apk_align" -> "对齐 ${args["path"] ?: "?"}"
        "apk_sign" -> "签名 ${args["path"] ?: "?"}（密钥 ${args["keystore_id"] ?: "default"}）"
        "apk_verify", "apk_info" -> "读取 ${args["path"] ?: "?"}"
        "package_info" -> "查询已安装应用 ${args["package"] ?: "?"}"
        "app_list" -> "列出已安装应用"
        "app_permissions" -> "读取 ${args["package"] ?: "?"} 的权限"
        "app_launch" -> "启动 ${args["package"] ?: "?"}"
        "app_stop" -> "强制停止 ${args["package"] ?: "?"}"
        "app_grant" -> if (args["grant"] == false) {
            "撤销 ${args["package"] ?: "?"} 的 ${args["permission"] ?: "?"}"
        } else {
            "授予 ${args["package"] ?: "?"} 的 ${args["permission"] ?: "?"}"
        }
        "app_debug" -> "设置调试应用 ${args["package"] ?: "?"}"
        "logcat" -> "读取日志" + (args["package"]?.let { "（$it）" } ?: "")
        "adb_status" -> "查看 ADB 连接状态"
        "adb_connect" -> "连接 ADB ${args["host"] ?: "127.0.0.1"}:${args["port"] ?: 5555}"
        "adb_shell" -> "adb shell ${args["command"] ?: "?"}"
        "apk_install" -> if (args["replace"] == true) {
            "卸载后安装 ${args["path"] ?: "?"}（会清除应用数据）"
        } else {
            "安装 ${args["path"] ?: "?"}"
        }
        "apk_uninstall" -> "卸载 ${args["package"] ?: "?"}（会清除应用数据）"
        else -> "$name " + args.entries.joinToString(" ") { "${it.key}=${it.value}" }
    }
}
