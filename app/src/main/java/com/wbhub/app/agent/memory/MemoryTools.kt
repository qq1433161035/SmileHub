package com.wbhub.app.agent.memory

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型可调用的记忆接口。
 *
 * 全部在主进程执行：记忆库只由主进程持有，跨进程只会多一份连接。
 * 写操作按 WRITE 风险走现有审批，读操作按 READ 自动放行。
 * 所有返回值都是 JSON，便于模型稳定解析。
 */
class MemoryTools(
    private val store: MemoryStore,
    private val indexer: MemoryIndexer,
    private val projectId: String = "",
) {

    data class Result(val text: String, val ok: Boolean)

    fun handles(name: String): Boolean = name.startsWith("memory_")

    fun call(name: String, args: Map<String, Any?>, sessionId: String): Result = when (name) {
        "memory_save" -> save(args, sessionId)
        "memory_update" -> update(args)
        "memory_search" -> search(args, sessionId)
        "memory_list" -> list(args)
        "memory_forget" -> forget(args, sessionId)
        else -> fail("unknown memory tool: $name")
    }

    private fun save(args: Map<String, Any?>, sessionId: String): Result {
        val text = args.str("text") ?: return fail("memory_save needs text")
        if (text.isBlank()) return fail("memory_save needs non-empty text")
        val type = MemoryType.from(args.str("memory_type"))
        val scope = args.str("scope")?.let { MemoryScope.from(it) }
            ?: if (type == MemoryType.PROFILE) MemoryScope.GLOBAL else MemoryScope.PROJECT
        val importance = (args["importance"] as? Number)?.toFloat() ?: 0.6f
        val result = indexer.saveManual(
            text = text,
            type = type,
            scope = scope,
            projectId = projectId,
            sessionId = sessionId,
            tags = args.str("tags").orEmpty(),
            importance = importance,
        )
        return when (result.action) {
            ACTION_SENSITIVE -> fail("内容疑似包含密钥或凭据，未写入")
            ACTION_TOO_LONG -> fail("超过 400 字，请拆成多条再保存")
            ACTION_UNAVAILABLE -> fail("长期记忆未开启或记忆库不可用")
            ACTION_ERROR -> fail("写入失败（记忆库错误）")
            ACTION_SKIPPED -> fail("未写入：内容为空或被过滤")
            ACTION_DUPLICATE -> ok(
                "action" to ACTION_DUPLICATE,
                "id" to result.id,
                "message" to "已存在相同记忆（id=${result.id}）",
            )
            ACTION_MERGED -> ok(
                "action" to ACTION_MERGED,
                "id" to result.id,
                "message" to "已合并到相近记忆（id=${result.id}）",
            )
            ACTION_SUPERSEDED -> ok(
                "action" to ACTION_SUPERSEDED,
                "id" to result.id,
                "message" to "已写入并替代旧结论（id=${result.id}）",
            )
            else -> ok(
                "action" to ACTION_NEW,
                "id" to result.id,
                "memory_type" to type.dbName,
                "message" to "已记住（id=${result.id}，${type.dbName}）",
            )
        }
    }

    private fun update(args: Map<String, Any?>): Result {
        val id = (args["id"] as? Number)?.toLong()
            ?: args.str("id")?.toLongOrNull()
            ?: return fail("memory_update needs id")
        val text = args.str("text") ?: return fail("memory_update needs text")
        if (text.isBlank()) return fail("text must not be empty")
        val result = indexer.update(id, text)
        if (result.ok) return ok("id" to id, "message" to "已更新记忆 $id")
        val message = when (result.reason) {
            "not_found" -> "记忆不存在、已过期或已被替代"
            "sensitive" -> "内容疑似包含密钥或凭据"
            "duplicate" -> "与已有记忆重复，请改用 memory_search 查看"
            "too_long" -> "超过 400 字，请拆成多条"
            "empty" -> "text must not be empty"
            else -> "更新失败（记忆库错误）"
        }
        return fail(message)
    }

    private fun search(args: Map<String, Any?>, sessionId: String): Result {
        val query = args.str("query") ?: return fail("memory_search needs query")
        val limit = ((args["top_k"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val type = args.str("memory_type")?.takeIf { it.isNotBlank() }?.let { MemoryType.from(it) }
        val hits = MemorySearch(store)
            .search(query, projectId, sessionId, excludeSession = false)
            .filter { type == null || it.record.type == type }
            .take(limit)
        val items = JSONArray()
        hits.forEach { hit ->
            items.put(
                jsonOf(hit.record).put("score", (hit.score * 1000).toInt() / 1000.0),
            )
        }
        return Result(JSONObject().put("ok", true).put("items", items).toString(), true)
    }

    private fun list(args: Map<String, Any?>): Result {
        val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 200)
        val type = args.str("memory_type")?.takeIf { it.isNotBlank() }?.let { MemoryType.from(it) }
        val scope = args.str("scope")?.takeIf { it.isNotBlank() }?.let { MemoryScope.from(it) }
        val items = JSONArray()
        store.list(type, scope, limit).forEach { items.put(jsonOf(it)) }
        return Result(JSONObject().put("ok", true).put("items", items).toString(), true)
    }

    private fun forget(args: Map<String, Any?>, sessionId: String): Result {
        val id = (args["id"] as? Number)?.toLong() ?: args.str("id")?.toLongOrNull()
        if (id != null) {
            return if (indexer.forget(id)) {
                ok("deleted" to 1, "message" to "已删除记忆 $id")
            } else {
                fail("记忆不存在：$id")
            }
        }
        val query = args.str("query") ?: return fail("memory_forget needs id or query")
        if (args["force"] != true) {
            return fail("按 query 删除会命中多条，请先 memory_search 确认，再加 force=true")
        }
        // 工具检索不排除当前会话，否则刚保存的条目按 query 删不掉。
        val hits = MemorySearch(store)
            .search(query, projectId, sessionId, excludeSession = false)
            .take(10)
        var removed = 0
        hits.forEach { if (indexer.forget(it.record.id)) removed++ }
        if (removed == 0) return fail("没有匹配的记忆")
        return ok("deleted" to removed, "message" to "已删除 $removed 条")
    }

    private fun jsonOf(record: MemoryRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("memory_type", record.type.dbName)
        put("scope", record.scope.dbName)
        put("project_id", record.projectId)
        put("session_id", record.sessionId)
        put("subject", record.subject)
        put("text", record.text)
        put("tags", record.tags)
        put("importance", record.importance)
        put("created_at", record.createdAt)
        put("updated_at", record.updatedAt)
        put("expires_at", record.expiresAt)
        put("superseded_by", record.supersededBy)
        if (record.sourcePath.isNotBlank()) put("source", record.sourcePath)
        put("use_count", record.useCount)
    }

    private fun ok(vararg pairs: Pair<String, Any>): Result {
        val json = JSONObject().put("ok", true)
        pairs.forEach { (key, value) -> json.put(key, value) }
        return Result(json.toString(), true)
    }

    private fun fail(message: String): Result =
        Result(JSONObject().put("ok", false).put("error", message).toString(), false)

    private fun Map<String, Any?>.str(key: String): String? = this[key] as? String
}
