package com.wbhub.app.data

import android.content.Context
import com.wbhub.app.storage.AppStorage
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 一条远程公告；字段与 ATool 的 notice.json 保持一致，只是不做 AES。 */
data class AppNotice(
    val title: String,
    val content: String,
    val time: String,
    val actionText: String = "",
    val actionUrl: String = "",
)

/**
 * 启动时拉取 SmileHub 明文公告。
 *
 * 与 ATool 的 Notice.java 相同的检查方式：
 * - 拉取成功才更新 last_check_at；
 * - 用 time（为空时退回 title）作为公告 id；
 * - 用户点过“不再提示”后，同一 id 不再弹；后台改 time 后会重新提示。
 */
object AppNoticeClient {

    private const val ENDPOINT = "https://weixiao.asia/apimodel/smilehub_notice.json"
    private const val PREFS = "wb-notice"
    private const val KEY_LAST_CHECK = "last_check_at"
    private const val KEY_DISMISSED = "dismissed_time"

    fun check(context: Context): AppNotice? {
        val prefs = AppStorage.privatePrefs(context, PREFS)
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SmileHub-App")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
        }
        return try {
            if (connection.responseCode !in 200..299) return null
            val body = InputStreamReader(connection.inputStream, Charsets.UTF_8).use { it.readText() }
            val notice = parseNotice(body) ?: return null
            // 拉取并解析成功就记录检查时间，哪怕公告已被“不再提示”。
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (noticeId(notice) == prefs.getString(KEY_DISMISSED, "").orEmpty()) null else notice
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** 用户选择“不再提示”时记录公告 id；后台改日期后会重新弹。 */
    fun dismiss(context: Context, notice: AppNotice) {
        AppStorage.privatePrefs(context, PREFS).edit()
            .putString(KEY_DISMISSED, noticeId(notice))
            .apply()
    }

    /** 最近一次成功拉取的时间；失败不更新时间。 */
    fun lastCheckAt(context: Context): Long =
        AppStorage.privatePrefs(context, PREFS).getLong(KEY_LAST_CHECK, 0L)

    /** 纯解析，便于单测；参数是已下载的 JSON 文本。 */
    fun parseNotice(text: String): AppNotice? {
        val json = JSONObject(text)
        val title = json.optString("title").trim()
        if (title.isEmpty()) return null
        val (actionText, actionUrl) = parseFunction(json.optString("function"))
        return AppNotice(
            title = title,
            content = json.optString("content").trim(),
            time = json.optString("time").trim(),
            actionText = actionText,
            actionUrl = actionUrl,
        )
    }

    private fun noticeId(notice: AppNotice): String =
        notice.time.ifEmpty { notice.title }

    /** 兼容 ATool 的 function 字段；SmileHub 只处理 3=打开链接。 */
    private fun parseFunction(raw: String): Pair<String, String> {
        val value = raw.trim()
        if (value.isEmpty()) return "" to ""
        val parts = value.split("#", limit = 2)
        if (parts.size != 2 || parts[0].trim() != "3") return "" to ""
        val url = parts[1].trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "" to ""
        return "查看详情" to url
    }
}
