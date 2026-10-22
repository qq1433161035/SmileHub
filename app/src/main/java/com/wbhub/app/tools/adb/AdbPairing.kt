package com.wbhub.app.tools.adb

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.wbhub.app.MainActivity
import com.wbhub.app.R
import kotlin.concurrent.thread

/**
 * 无线调试：跳转系统页面 + 通知栏输入配对码。
 *
 * 系统的「使用配对码配对设备」弹窗在 HyperOS 上只要离开设置页就会被销毁，
 * 所以配对码通过通知栏输入：下拉通知栏不会让设置页退到后台，配对会话还能活着。
 * 悬浮窗会和系统配对弹窗抢窗口，只能二选一，因此这里仍用 RemoteInput。
 */
object AdbPairing {
    const val CHANNEL = "wb_adb_pair"
    const val KEY_CODE = "adb_pair_code"
    private const val NOTIFICATION_ID = 8767
    private const val ACTION_PAIR = "com.wbhub.app.action.ADB_PAIR_CODE"

    /** 跳到系统的无线调试页面（Settings.SubSettings + 指定 fragment）。 */
    fun openWirelessDebugging(context: Context) {
        val direct = Intent().setComponent(
            ComponentName("com.android.settings", "com.android.settings.SubSettings"),
        ).putExtra(":settings:show_fragment", "com.android.settings.development.WirelessDebuggingFragment")
            .putExtra(":settings:show_fragment_title", "无线调试")
        val aliases = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings\$WirelessDebuggingActivity"),
            ComponentName("com.android.settings", "com.android.settings.development.WirelessDebuggingActivity"),
        ).map { Intent().setComponent(it) }
        val fallback = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        for (intent in listOf(direct) + aliases + fallback) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /** 发一条带输入框的通知；返回 false 表示通知发送失败。 */
    fun show(context: Context): Boolean = runCatching {
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("配对码")
            .build()
        val intent = Intent(context, AdbPairReceiver::class.java).setAction(ACTION_PAIR)
        val pending = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_hub,
            "输入",
            pending,
        ).addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentIntent(openApp(context))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentTitle("无线调试输入")
            // 有正文时通知按标准高度展开，输入行不会被挤成异常比例。
            .setContentText("输入配对码后自动配对并连接")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(action)
            .setOnlyAlertOnce(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        true
    }.getOrDefault(false)

    fun update(context: Context, text: String, done: Boolean) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle(if (done) "无线调试" else "无线调试配对中…")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(!done)
            .setAutoCancel(done)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                "无线调试配对",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "输入无线调试的配对码" },
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/** 接收通知栏输入的配对码，配对成功后立即连接。 */
class AdbPairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AdbPairing.KEY_CODE)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (code.isEmpty()) return
        AdbPairing.update(context, "正在用配对码 $code 配对…", done = false)
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "wbhub-adb-pair") {
            try {
                val paired = AdbSession.pair(app, code)
                if (!paired.ok) {
                    AdbPairing.update(app, "配对失败：${paired.text}", done = true)
                    return@thread
                }
                val connected = AdbSession.connect(app)
                val message = if (connected.ok) {
                    "配对并连接成功：${AdbSession.endpoint()}（${connected.text.trim()}）"
                } else {
                    "配对成功，但连接失败：${connected.text}\n回到设置页会显示具体状态"
                }
                AdbPairing.update(app, message, done = true)
            } finally {
                pending.finish()
            }
        }
    }
}
