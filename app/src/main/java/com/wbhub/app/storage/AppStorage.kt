package com.wbhub.app.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * One place for everything the app persists.
 *
 * Shared storage is deliberate: the agent, adb and the engineering tools all
 * need to read the same files, and the user needs to be able to inspect,
 * back up or edit them with a file manager. The only fallback is when the
 * all-files permission has not been granted yet — in that case a private
 * directory keeps the app usable instead of crashing.
 */
object AppStorage {

    const val DIR_SETTINGS = "settings"
    const val DIR_CREDENTIALS = "credentials"
    const val DIR_CERTIFICATES = "certificates"
    const val DIR_RECORDS = "records"
    const val DIR_SESSIONS = "agent/sessions"
    const val DIR_KEYS = "signing/keys"
    const val DIR_DATABASE = "database"
    const val DIR_WORKSPACE = "workspace"
    const val DIR_LOGS = "logs"
    const val DIR_TOOLS = "tools"
    const val DIR_LIBS = "libs"
    const val DIR_SECRETS = "secrets"
    const val DIR_ADB = "adb"
    const val DIR_MEMORY = "memory"

    private const val ROOT_NAME = "WBHub"

    private val prefCache = ConcurrentHashMap<String, FilePrefs>()

    fun root(context: Context): File {
        val shared = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        // 只有“存在且可写”才用共享目录。仅判断存在会在撤权后静默丢设置。
        if ((shared.isDirectory || shared.mkdirs()) && shared.canWrite()) return shared
        // No all-files access: stay on internal storage rather than failing.
        return File(context.filesDir, ROOT_NAME).apply { mkdirs() }
    }

    /** 先写临时文件再 rename，避免进程被杀时留下截断的 JSON。 */
    fun writeAtomically(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    fun dir(context: Context, path: String): File =
        File(root(context), path).apply { mkdirs() }

    /**
     * 凭据、ADB 私钥、TLS CA 私钥的存放根目录。
     *
     * 这些文件不能放在 `/sdcard`：那里的内容任何具备存储权限的应用都能读。
     * 位置固定在应用私有目录，不再从共享存储迁移。
     */
    fun secrets(context: Context): File =
        File(context.filesDir, ROOT_NAME).resolve(DIR_SECRETS).apply { mkdirs() }

    private fun secretDir(context: Context, name: String): File =
        File(secrets(context), name).apply { mkdirs() }

    fun settings(context: Context): File = dir(context, DIR_SETTINGS)

    /**
     * 仅应用可写的设置目录。
     *
     * 共享存储里的 JSON 任何具备存储访问能力的应用都能改写；提示词一旦被改，
     * 等于给每次模型请求注入指令，所以这类值单独放在私有目录，只由设置页编辑。
     */
    fun privateSettings(context: Context): File =
        File(context.filesDir, ROOT_NAME).resolve(DIR_SETTINGS).apply { mkdirs() }

    fun credentials(context: Context): File = secretDir(context, DIR_CREDENTIALS)
    fun certificates(context: Context): File = secretDir(context, DIR_CERTIFICATES)
    fun adb(context: Context): File = secretDir(context, DIR_ADB)
    fun records(context: Context): File = dir(context, DIR_RECORDS)
    fun sessions(context: Context): File = dir(context, DIR_SESSIONS)
    /** 签名密钥与凭据同级：共享存储里的 p12 任何应用都能复制。 */
    fun keys(context: Context): File = File(secrets(context), DIR_KEYS).apply { mkdirs() }
    fun database(context: Context): File = dir(context, DIR_DATABASE)
    fun workspace(context: Context): File = dir(context, DIR_WORKSPACE)
    fun logs(context: Context): File = dir(context, DIR_LOGS)
    fun tools(context: Context): File = dir(context, DIR_TOOLS)
    fun libs(context: Context): File = dir(context, DIR_LIBS)

    /**
     * 长期记忆库。
     *
     * 放在私有目录而不是共享存储：记忆里会有用户偏好和项目细节，
     * 不应该被其他应用读到。
     */
    fun memory(context: Context): File =
        File(context.filesDir, ROOT_NAME).resolve(DIR_MEMORY).apply { mkdirs() }

    /** Preferences backed by a JSON file under settings/, not by /data/data. */
    fun prefs(context: Context, name: String): FilePrefs {
        val file = File(settings(context), "$name.json")
        return prefCache.getOrPut(file.absolutePath) { FilePrefs(file) }
    }

    /** 私有目录里的 preferences：外部应用不可读写。 */
    fun privatePrefs(context: Context, name: String): FilePrefs {
        val file = File(privateSettings(context), "$name.json")
        return prefCache.getOrPut(file.absolutePath) { FilePrefs(file) }
    }
}
