package com.wbhub.app.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.wbhub.app.MainActivity
import com.wbhub.app.R

/** Notification plumbing: the bridge's ongoing notification and login alerts. */
object Notifications {

    const val BRIDGE_CHANNEL = "wb_bridge"
    const val LOGIN_CHANNEL = "wb_login"
    const val AGENT_CHANNEL = "wb_agent"
    private const val BRIDGE_NOTIFICATION = 8765
    private const val LOGIN_NOTIFICATION = 8766

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                BRIDGE_CHANNEL,
                "SmileHub 转发服务",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "本地 API 平台运行状态" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LOGIN_CHANNEL,
                "登录提醒",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "凭证获取结果" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                AGENT_CHANNEL,
                "智能体运行",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "长任务进度与后台保活" },
        )
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun bridgeId() = BRIDGE_NOTIFICATION

    /**
     * Keeps a notification in the shade as soon as the app opens, so the service
     * is not the first time the user sees one. Replaced by the running
     * notification once the bridge is started.
     */
    fun showBridgeReady(context: Context, port: Int) {
        if (!hasPermission(context)) return
        val notification = NotificationCompat.Builder(context, BRIDGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle("SmileHub")
            .setContentText("已就绪 · API 平台端口 $port")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent(context))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(BRIDGE_NOTIFICATION, notification)
    }

    /** Removes the ongoing notification, e.g. when the user stops the bridge. */
    fun cancelBridge(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(BRIDGE_NOTIFICATION)
    }

    private fun contentIntent(context: Context, extra: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (extra != null) putExtra(extra, true)
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun buildBridge(context: Context, port: Int) =
        NotificationCompat.Builder(context, BRIDGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle("SmileHub 本地 API 平台")
            .setContentText("127.0.0.1:$port 运行中")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent(context))
            .build()

    /** Confirms a successful sign-in; tapping returns to the app. */
    fun showLoginSuccess(context: Context, nickname: String) {
        if (!hasPermission(context)) return
        val text = if (nickname.isBlank()) "凭证已保存" else "已登录：$nickname"
        val notification = NotificationCompat.Builder(context, LOGIN_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle("SmileHub 登录成功")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, EXTRA_OPEN_AFTER_LOGIN))
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(LOGIN_NOTIFICATION, notification)
    }

    const val EXTRA_OPEN_AFTER_LOGIN = "open_after_login"
}
