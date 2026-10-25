package com.wbhub.app.storage

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * SharedPreferences with a JSON file as its backing store.
 *
 * The API is implemented rather than wrapped so existing call sites stay
 * unchanged, while the bytes land in shared storage where the user can see
 * them. Writes go through a temp file so a crash cannot truncate the settings.
 */
class FilePrefs(private val file: File) : SharedPreferences {

    private val values = LinkedHashMap<String, Any?>()
    private val listeners = CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

    /** 文件版本戳（修改时间 + 长度），用于发现另一个进程写入了新值。 */
    private var stamp: Long = -1L

    init {
        file.parentFile?.mkdirs()
        load()
    }

    private fun fileStamp(): Long =
        if (file.isFile) file.lastModified() * 31 + file.length() else -1L

    @Synchronized
    private fun load() {
        values.clear()
        if (file.isFile) {
            runCatching {
                val json = JSONObject(file.readText())
                json.keys().forEach { key -> fromJson(json.get(key))?.let { values[key] = it } }
            }
        }
        stamp = fileStamp()
    }

    /**
     * 两个进程各有一份内存缓存；读之前先看文件是否被对方改过。
     * 不重载的话，`:tools` 会一直用旧开关值，直到进程重启。
     */
    @Synchronized
    private fun refreshIfChanged() {
        val current = fileStamp()
        if (current != stamp) load()
    }

    private fun fromJson(value: Any?): Any? = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it) }.toSet()
        is Int, is Long, is Double, is Boolean, is String -> value
        else -> null
    }

    private fun toJson(value: Any?): Any? = when (value) {
        is Set<*> -> JSONArray(value.toList())
        else -> value
    }

    @Synchronized
    private fun persist() {
        val json = JSONObject()
        values.forEach { (key, value) -> toJson(value)?.let { json.put(key, it) } }
        val text = json.toString()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
        stamp = fileStamp()
    }

    @Synchronized
    private fun value(key: String?): Any? {
        refreshIfChanged()
        return values[key]
    }

    override fun getAll(): MutableMap<String, *> = synchronized(this) {
        refreshIfChanged()
        LinkedHashMap(values)
    }

    override fun getString(key: String?, defValue: String?): String? = value(key) as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val stored = value(key) as? Set<*> ?: return defValues
        return stored.mapNotNull { it as? String }.toMutableSet()
    }

    override fun getInt(key: String?, defValue: Int): Int = (value(key) as? Number)?.toInt() ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = (value(key) as? Number)?.toLong() ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = (value(key) as? Number)?.toFloat() ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        value(key) as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = synchronized(this) {
        refreshIfChanged()
        values.containsKey(key)
    }

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) {
        listener ?: return
        listeners.remove(listener)
    }

    /** Bulk import used by the one-time migration from the XML preferences. */
    @Synchronized
    fun putAll(entries: Map<String, *>) {
        entries.forEach { (key, value) -> if (value != null) values[key] = value }
        persist()
    }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = LinkedHashMap<String, Any?>()
        private val removals = LinkedHashSet<String>()
        private var cleared = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = value
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = values?.toSet()
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = value
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = value
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = value
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
            changes[key.orEmpty()] = value
        }

        override fun remove(key: String?): SharedPreferences.Editor = apply {
            removals.add(key.orEmpty())
        }

        override fun clear(): SharedPreferences.Editor = apply { cleared = true }

        override fun commit(): Boolean {
            synchronized(this@FilePrefs) {
                // 先合并另一个进程刚写入的键，避免整文件覆盖把对方的修改抹掉。
                refreshIfChanged()
                if (cleared) values.clear()
                removals.forEach { values.remove(it) }
                changes.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                return runCatching { persist() }.isSuccess
            }
        }

        override fun apply() {
            commit()
        }
    }
}
