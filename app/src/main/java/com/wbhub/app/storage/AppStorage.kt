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

    private const val ROOT_NAME = "WBHub"
    private const val MIGRATION_MARKER = ".migration-v1"

    private val prefCache = ConcurrentHashMap<String, FilePrefs>()

    @Volatile
    private var initialised = false

    /** Must run before any store is constructed. Safe to call repeatedly. */
    @Synchronized
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        val rootDir = root(app)
        val marker = File(rootDir, MIGRATION_MARKER)
        if (marker.exists()) return
        val result = runCatching { migrateLegacy(app) }
        result.exceptionOrNull()?.let {
            android.util.Log.w("WBHub", "storage migration incomplete", it)
        }
        if (result.isSuccess) {
            runCatching { marker.writeText("1") }
        }
    }

    fun root(context: Context): File {
        val shared = File(Environment.getExternalStorageDirectory(), ROOT_NAME)
        if (shared.isDirectory || shared.mkdirs() || shared.canWrite()) return shared
        // No all-files access: stay on internal storage rather than failing.
        return File(context.filesDir, ROOT_NAME).apply { mkdirs() }
    }

    fun dir(context: Context, path: String): File =
        File(root(context), path).apply { mkdirs() }

    fun settings(context: Context): File = dir(context, DIR_SETTINGS)
    fun credentials(context: Context): File = dir(context, DIR_CREDENTIALS)
    fun certificates(context: Context): File = dir(context, DIR_CERTIFICATES)
    fun records(context: Context): File = dir(context, DIR_RECORDS)
    fun sessions(context: Context): File = dir(context, DIR_SESSIONS)
    fun keys(context: Context): File = dir(context, DIR_KEYS)
    fun database(context: Context): File = dir(context, DIR_DATABASE)
    fun workspace(context: Context): File = dir(context, DIR_WORKSPACE)
    fun logs(context: Context): File = dir(context, DIR_LOGS)
    fun tools(context: Context): File = dir(context, DIR_TOOLS)
    fun libs(context: Context): File = dir(context, DIR_LIBS)

    /** Preferences backed by a JSON file under settings/, not by /data/data. */
    fun prefs(context: Context, name: String): FilePrefs {
        val file = File(settings(context), "$name.json")
        return prefCache.getOrPut(file.absolutePath) { FilePrefs(file) }
    }

    // ------------------------------------------------------------------ //
    // One-time move of everything the previous builds wrote into private
    // storage. Originals are left in place so a failed copy loses nothing.
    // ------------------------------------------------------------------ //

    /**
     * Only the account survives the move. Sessions, call logs, the project
     * database, signing keys, certificates, workspace files and every setting
     * are deliberately left behind so the new layout starts clean and the user
     * configures it once.
     */
    private fun migrateLegacy(context: Context) {
        context.filesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("wb-auth-")) {
                copy(file, credentials(context))
            }
        }
    }

    private fun copy(source: File, targetDir: File) {
        val target = File(targetDir, source.name)
        if (target.exists()) return
        runCatching { source.copyTo(target, overwrite = false) }
    }
}
