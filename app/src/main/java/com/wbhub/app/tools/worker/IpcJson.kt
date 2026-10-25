package com.wbhub.app.tools.worker

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON 与 Kotlin 集合之间的转换。
 *
 * 工具参数与工具定义在进程间只能以字符串传递，两边都需要在
 * `Map<String, Any?>` 与 JSON 之间来回转一次。
 */
internal fun jsonToValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { jsonToValue(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { jsonToValue(value.opt(it)) }
    else -> value
}

internal fun jsonToMap(json: String): Map<String, Any?> {
    if (json.isBlank()) return emptyMap()
    val obj = JSONObject(json)
    return obj.keys().asSequence().associateWith { jsonToValue(obj.opt(it)) }
}

internal fun Map<String, Any?>.toJson(): String = JSONObject(this as Map<*, *>).toString()

/** 从 OpenAI 风格的 function 定义里取工具名。 */
internal fun toolNameOf(definition: Map<String, Any>): String? =
    ((definition["function"] as? Map<*, *>)?.get("name") as? String)
