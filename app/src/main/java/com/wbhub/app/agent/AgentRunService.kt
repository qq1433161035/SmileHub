package com.wbhub.app.agent

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.wbhub.app.MainActivity
import com.wbhub.app.R
import com.wbhub.app.bridge.Notifications

/**
 * 智能体运行期间的前台服务。
 *
 * 两个作用：
 * 1. 让进程保持前台重要性。调试别的应用时本应用会退到后台，没有前台服务
 *    会被系统冻结，SSE 连接随之中断，会话看起来像“自己断了”。
 * 2. 通知栏显示当前进度（工具名、已运行时间），长任务不必盯着屏幕。
 *
 * 只在有会话在跑时存在，任务结束即停止；它与转发服务的通知互不影响。
 */
class AgentRunService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var title = "智能体运行中"
    private var text = "正在思考…"

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notifications.ensureChannel(this)
        wakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmileHub:agent")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure {
            android.util.Log.w(TAG, "agent wake lock unavailable", it)
        }.getOrNull()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
        intent?.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { text = it }
        runCatching { startForeground(NOTIFICATION_ID, build()) }.onFailure {
            android.util.Log.w(TAG, "agent foreground refused", it)
        }
        return START_NOT_STICKY
    }

    private fun build() =
        NotificationCompat.Builder(this, Notifications.AGENT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hub)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .build()

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun publish() {
        if (!Notifications.hasPermission(this)) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build())
        }
    }

    override fun onDestroy() {
        instance = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        runCatching { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "WBHub"
        private const val NOTIFICATION_ID = 8770
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        @Volatile
        private var instance: AgentRunService? = null

        /** 开始或复用前台服务；标题取会话名。 */
        fun start(context: Context, sessionTitle: String) {
            if (!Notifications.hasPermission(context)) return
            val intent = Intent(context, AgentRunService::class.java).apply {
                putExtra(EXTRA_TITLE, sessionTitle.ifBlank { "智能体运行中" })
                putExtra(EXTRA_TEXT, "正在思考…")
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { android.util.Log.w(TAG, "cannot start agent service", it) }
        }

        /** 更新通知内容；服务没在跑时忽略。 */
        fun update(text: String) {
            val service = instance ?: return
            service.text = text.take(200)
            service.publish()
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentRunService::class.java)) }
        }
    }
}
