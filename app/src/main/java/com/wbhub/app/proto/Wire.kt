package com.wbhub.app.proto

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Kotlin port of the WorkBuddy wire protocol.
 *
 * Every rule here is a measured quirk of the upstream gateway, not a design
 * choice: the request body must be reshaped, the identity headers must be
 * present, and the model roster is an intersection rather than a flat list.
 * Ported from the reference implementation so the bridge behaves identically.
 */
object Wire {

    const val CN_CHAT_BASE = "https://copilot.tencent.com"
    const val CN_BILLING_BASE = "https://www.codebuddy.cn"
    const val GLOBAL_BASE = "https://www.workbuddy.ai"

    /** The CLI-shaped user agent the CN endpoints expect. */
    const val CLIENT_UA = "CLI/2.63.2 CodeBuddy/2.63.2"

    const val ERROR_BODY_LIMIT = 4096
    const val JSON_TIMEOUT_MS = 30_000

    private const val BADGE_PREFIX = "badge:"
    private val EFFORT_VALUES = setOf("low", "medium", "high", "xhigh", "max")

    /** Insufficient-credit markers the gateway reports in prose, not status codes. */
    private val HARD_CREDIT_MARKERS = listOf(
        "insufficient credit", "no credit", "credit exhausted", "credits exhausted", "out of credit",
        "quota exceeded", "quota exhaust", "payment required", "credit not enough",
        "not enough credit",
        "积分不足", "额度不足", "余额不足", "积分用完", "额度用尽", "没有积分",
    )

    /** A dead session is sometimes reported as 403, so the body is checked too. */
    private val SESSION_DEAD_MARKERS = listOf("Offline user session not found", "12153")

    /** Failure classes, mapped onto distinct HTTP answers by the bridge. */
    enum class ErrorKind { HARD_CREDIT, SOFT_RATE, SESSION_DEAD, NOT_FOUND, SERVER, CLIENT }

    /** Which build a credential belongs to; an empty domain means CN. */
    fun regionOf(domain: String): Region {
        val lowered = domain.trim().lowercase()
        return if (lowered == "workbuddy.ai" || lowered.endsWith(".workbuddy.ai")) Region.GLOBAL else Region.CN
    }

    enum class Region { CN, GLOBAL }

    fun chatBase(region: Region): String = if (region == Region.GLOBAL) GLOBAL_BASE else CN_CHAT_BASE

    fun billingBase(region: Region): String = if (region == Region.GLOBAL) GLOBAL_BASE else CN_BILLING_BASE

    fun originOf(region: Region): String = if (region == Region.GLOBAL) GLOBAL_BASE else CN_BILLING_BASE

    /** Classifies a failure from its status and body excerpt. */
    fun classify(status: Int, body: String): ErrorKind {
        if (status == 402) return ErrorKind.HARD_CREDIT
        // A 401 is always an auth failure on this upstream, whatever the body says.
        if (status == 401) return ErrorKind.SESSION_DEAD
        val lowered = body.lowercase()
        if (HARD_CREDIT_MARKERS.any { lowered.contains(it.lowercase()) || body.contains(it) }) {
            return ErrorKind.HARD_CREDIT
        }
        if (SESSION_DEAD_MARKERS.any { body.contains(it) }) return ErrorKind.SESSION_DEAD
        return when {
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status >= 500 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
    }

    /**
     * Pulls the user-facing message out of an error body, preferring the
     * localized `displayMsg` the upstream sends.
     */
    fun displayError(body: String): String? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return null
        return runCatching {
            val obj = JSONObject(trimmed)
            val display = obj.optJSONObject("displayMsg")
            if (display != null) {
                val zh = display.optString("zh").trim()
                if (zh.isNotEmpty()) return@runCatching zh
                val en = display.optString("en").trim()
                if (en.isNotEmpty()) return@runCatching en
            }
            obj.optString("msg").trim().ifEmpty { null }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ //
    // Request body shaping
    // ------------------------------------------------------------------ //

    /**
     * Forces streaming, rewrites `developer` to `system`, and flattens
     * `tool_choice`.
     *
     * The `developer` rewrite is load-bearing: OpenAI clients emit the system
     * prompt that way, and the gateway rejects it with 400 code 11128.
     */
    fun prepareChatBody(source: String): String {
        val obj = runCatching { JSONObject(source) }.getOrNull() ?: return source
        return prepareChatBodyObject(obj).toString()
    }

    /**
     * The same shaping, on a body that is already parsed.
     *
     * The bridge holds a request as an object, so handing it to the string
     * entry point would serialise it only to parse it straight back — a copy of
     * several megabytes, paid on every call and sitting in front of the first
     * token the client is waiting for.
     */
    fun prepareChatBodyObject(obj: JSONObject): JSONObject {
        obj.put("stream", true)
        normalizeDeveloperRole(obj)
        normalizeToolChoice(obj)
        return obj
    }

    private fun normalizeDeveloperRole(obj: JSONObject) {
        val messages = obj.optJSONArray("messages") ?: return
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") == "developer") message.put("role", "system")
        }
    }

    private fun normalizeToolChoice(obj: JSONObject) {
        if (!obj.has("tool_choice")) return
        val suppress = {
            obj.remove("tools")
            obj.remove("functions")
        }
        when (val choice = obj.opt("tool_choice")) {
            is String -> if (choice.trim().lowercase() == "none") {
                obj.remove("tool_choice")
                suppress()
            }
            is JSONObject -> {
                when (choice.optString("type").trim().lowercase()) {
                    "none" -> {
                        obj.remove("tool_choice")
                        suppress()
                    }
                    "auto", "required" -> obj.put("tool_choice", choice.optString("type").trim().lowercase())
                    "function" -> {
                        val fn = choice.optJSONObject("function")
                        var name = fn?.optString("name").orEmpty()
                        if (name.isEmpty()) name = choice.optString("name")
                        name = name.trim()
                        obj.put("tool_choice", if (name.isNotEmpty()) name else "auto")
                    }
                    else -> obj.remove("tool_choice")
                }
            }
            else -> obj.remove("tool_choice")
        }
    }

    /**
     * The international gateway additionally requires a leading `system`
     * message and rejects the adapter's `off` effort spelling.
     */
    fun prepareInternationalChatBody(source: String): String {
        val obj = runCatching { JSONObject(source) }.getOrNull() ?: return source
        return prepareInternationalChatBodyObject(obj).toString()
    }

    /**
     * The international shaping, on a body that is already parsed.
     *
     * See [prepareChatBodyObject] for why the object form exists.
     */
    fun prepareInternationalChatBodyObject(obj: JSONObject): JSONObject {
        prepareChatBodyObject(obj)
        if (obj.optString("reasoning_effort") == "off") obj.remove("reasoning_effort")
        val messages = obj.optJSONArray("messages") ?: return obj
        val first = messages.optJSONObject(0)
        if (first != null && first.optString("role") == "system") return obj
        val rebuilt = JSONArray()
        rebuilt.put(JSONObject().put("role", "system").put("content", INTERNATIONAL_SYSTEM_PROMPT))
        for (i in 0 until messages.length()) rebuilt.put(messages.opt(i))
        obj.put("messages", rebuilt)
        return obj
    }

    /**
     * 国际版网关要求的默认 system 提示词。
     *
     * 公开给 Agent 侧：注入长期记忆时会自己补一条 system，若仍走
     * [prepareInternationalChatBodyObject] 的“首条是 system 就直接返回”，
     * 默认提示词会被悄悄丢掉。
     */
    const val INTERNATIONAL_SYSTEM_PROMPT = "You are a helpful assistant."

    // ------------------------------------------------------------------ //
    // Headers
    // ------------------------------------------------------------------ //

    /** Headers shared by every upstream request. */
    fun commonHeaders(region: Region, uid: String, domain: String): MutableMap<String, String> =
        mutableMapOf(
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to originOf(region),
            "Referer" to "${originOf(region)}/",
            "User-Agent" to CLIENT_UA,
        )

    /**
     * Chat headers, including the `X-No-*` conventions the official client uses
     * to state that a value is deliberately absent.
     *
     * [conversationId] is the gateway's channel check: since 2026-10-15 the
     * international gateway refuses any chat request that does not carry the
     * conversation header trio, answering 11128 "request illegal" with text
     * about the call not coming from an official client. The domestic gateway
     * does not check yet, but the headers are sent there too — they are ignored
     * when not required, and a later change would otherwise break it silently.
     */
    fun chatHeaders(
        region: Region,
        accessToken: String,
        uid: String,
        domain: String,
        enterpriseId: String?,
        userAgent: String,
        clientVersion: String,
        conversationId: String = newConversationId(),
    ): Map<String, String> {
        val headers = commonHeaders(region, uid, domain)
        headers["User-Agent"] = userAgent
        headers["Content-Type"] = "application/json"
        headers["Authorization"] = "Bearer $accessToken"
        if (uid.isEmpty()) headers["X-No-User-Id"] = "1" else headers["X-User-Id"] = uid
        if (enterpriseId.isNullOrEmpty()) {
            headers["X-No-Enterprise-Id"] = "1"
        } else {
            headers["X-Enterprise-Id"] = enterpriseId
        }
        if (domain.isEmpty()) headers["X-No-Department-Info"] = "1" else headers["X-Domain"] = domain
        headers["X-IDE-Type"] = "WorkBuddy"
        headers["X-IDE-Name"] = "WorkBuddy"
        headers["X-IDE-Version"] = clientVersion
        headers["X-Product"] = "SaaS"
        // The channel trio. The conversation id stays the same across the turns
        // of one conversation so the gateway can follow it; the other two are
        // per-request and never reused.
        val messageId = newConversationId()
        headers["X-Conversation-ID"] = conversationId
        headers["X-Conversation-Request-ID"] = newConversationId()
        headers["X-Conversation-Message-ID"] = messageId
        // The gateway reads this as the same identifier; keeping them equal is
        // what the official client sends.
        headers["X-Request-ID"] = messageId
        return headers
    }

    /** A random identifier in the form the gateway expects. */
    fun newConversationId(): String = java.util.UUID.randomUUID().toString()

    /**
     * A conversation id that stays the same across the turns of one chat.
     *
     * The gateway wants a stable id but does not say what to derive it from, so
     * the opening user turn is used: a client resending a conversation carries
     * the same opening turn, while a new conversation almost always opens
     * differently. When no opening turn can be found a random id serves just as
     * well, because the gateway only requires the header to be present.
     */
    fun conversationIdFor(request: JSONObject): String {
        val messages = request.optJSONArray("messages")
        if (messages != null) {
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: continue
                if (message.optString("role") != "user") continue
                val text = flattenForSeed(message.opt("content"))
                if (text.isNotEmpty()) return digestId(text)
            }
        }
        // A Responses-shaped body keeps its turns under `input`; its opening
        // user turn identifies the conversation just as well.
        val input = request.optJSONArray("input")
        if (input != null) {
            for (i in 0 until input.length()) {
                val item = input.optJSONObject(i) ?: continue
                if (item.optString("role") != "user") continue
                val text = flattenForSeed(item.opt("content"))
                if (text.isNotEmpty()) return digestId(text)
            }
        }
        return newConversationId()
    }

    /** A name-based UUID, so the same text always yields the same id. */
    private fun digestId(seed: String): String =
        java.util.UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8)).toString()

    private fun flattenForSeed(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            val part = value.optJSONObject(i)
            when {
                part == null -> ""
                part.has("text") -> part.optString("text")
                part.has("content") -> flattenForSeed(part.opt("content"))
                else -> ""
            }
        }
        else -> ""
    }

    /** Refresh headers; the refresh token appears here and nowhere else. */
    fun refreshHeaders(region: Region, refreshToken: String, enterpriseId: String?): Map<String, String> {
        val headers = commonHeaders(region, "", "")
        headers["X-Refresh-Token"] = refreshToken
        headers["X-Auth-Refresh-Source"] = "workbuddy"
        if (!enterpriseId.isNullOrEmpty()) headers["X-Enterprise-Id"] = enterpriseId
        return headers
    }

    /** Billing headers; this path authenticates with the bearer token. */
    fun billingHeaders(region: Region, accessToken: String, uid: String, domain: String, enterpriseId: String?): Map<String, String> {
        val headers = mutableMapOf(
            "Authorization" to "Bearer $accessToken",
            "Accept" to "application/json",
            "Content-Type" to "application/json",
        )
        if (uid.isNotEmpty()) headers["X-User-Id"] = uid
        if (!enterpriseId.isNullOrEmpty()) {
            headers["X-Enterprise-Id"] = enterpriseId
            headers["X-Tenant-Id"] = enterpriseId
        }
        if (domain.isNotEmpty()) headers["X-Domain"] = domain
        return headers
    }

    /**
     * Growth-domain headers: the billing set plus request provenance.
     *
     * The growth endpoints sit on the chat base but authenticate like billing,
     * and the upstream additionally expects the origin/referer pair the official
     * web client sends, so both halves are combined here.
     */
    fun growthHeaders(region: Region, accessToken: String, uid: String, domain: String, enterpriseId: String?): Map<String, String> {
        val headers = billingHeaders(region, accessToken, uid, domain, enterpriseId).toMutableMap()
        headers["Origin"] = originOf(region)
        headers["Referer"] = "${originOf(region)}/"
        return headers
    }

    // ------------------------------------------------------------------ //
    // Model catalogue
    // ------------------------------------------------------------------ //

    /**
     * trims an upstream credits string to its language-neutral form.
     * `"x0.79 credits"` becomes `"x0.79"`; a bare unit word carries no rate.
     */
    fun normalizeCredits(credits: String?): String? {
        val trimmed = credits?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (Regex("(?i)^credits?$").matches(trimmed)) return null
        val bare = trimmed.replace(Regex("(?i)\\s+credits?$"), "").trim()
        return bare.ifEmpty { null }
    }

    /**
     * The roster is the `cli` agent's model list intersected with the rows that
     * are actually servable, so a published-but-unusable id never reaches a
     * client. Throws when either half is missing, because an empty catalogue
     * means the document changed shape.
     */
    fun parseModels(document: String): List<ProtoModel> {
        val root = JSONObject(document)
        val data = root.optJSONObject("data")
            ?: if (root.has("models") || root.has("agents")) root else JSONObject()
        val rawModels = data.optJSONArray("models") ?: JSONArray()
        val agents = data.optJSONArray("agents") ?: JSONArray()

        var cliIds: List<String>? = null
        for (i in 0 until agents.length()) {
            val agent = agents.optJSONObject(i) ?: continue
            if (agent.optString("name") == "cli") {
                val list = agent.optJSONArray("models") ?: continue
                cliIds = (0 until list.length()).map { list.optString(it) }.filter { it.isNotEmpty() }
                break
            }
        }
        if (cliIds.isNullOrEmpty()) throw IllegalStateException("catalog lists no cli agent models")

        val byId = HashMap<String, ProtoModel>()
        for (i in 0 until rawModels.length()) {
            val row = rawModels.optJSONObject(i) ?: continue
            val id = row.optString("id")
            if (id.isEmpty() || row.optBoolean("disabled")) continue
            val input = row.optInt("maxInputTokens", 0)
            val output = row.optInt("maxOutputTokens", 0)
            if (input <= 0 || output <= 0) continue
            val window = row.optJSONObject("contextWindow")
            val defaultLength = window?.optInt("defaultLength", 0) ?: 0
            val supported = window?.optJSONArray("supportedLengths")
                ?.let { arr -> (0 until arr.length()).map { arr.optInt(it) }.filter { it > 0 } }
                ?: emptyList()
            byId[id] = ProtoModel(
                id = id,
                name = row.optString("name").ifEmpty { id },
                contextWindow = if (defaultLength > 0) defaultLength else input,
                defaultContextWindow = if (defaultLength > 0) defaultLength else null,
                maxInputTokens = input,
                supportedContextWindows = supported,
                maxTokens = output,
                // Over-claiming image input would admit a picture the provider
                // later rejects, so unknown resolves to false.
                supportsImages = row.optBoolean("supportsImages") && !row.optBoolean("disabledMultimodal"),
                reasoning = resolveReasoning(row),
                credits = row.optString("credits").ifEmpty { null },
                badges = parseBadges(row.optJSONArray("tags")),
            )
        }
        val models = cliIds.mapNotNull { byId[it] }
        if (models.isEmpty()) throw IllegalStateException("catalog resolved to an empty list")
        return models
    }

    private fun resolveReasoning(row: JSONObject): ProtoReasoning {
        val supports = row.optBoolean("supportsReasoning")
        val onlyReasoning = row.optBoolean("onlyReasoning")
        val raw = row.optJSONObject("reasoning")
        val efforts = raw?.optJSONArray("supportedEfforts")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
            ?.filter { it in EFFORT_VALUES }
            ?.takeIf { it.isNotEmpty() }
        val default = raw?.optString("defaultEffort")?.takeIf { it in EFFORT_VALUES }
            ?: raw?.optString("effort")?.takeIf { it in EFFORT_VALUES }
        return ProtoReasoning(
            supports = supports,
            onlyReasoning = onlyReasoning,
            supportedEfforts = efforts ?: emptyList(),
            defaultEffort = default,
            // Only an explicit true allows switching thinking off; older rows
            // omit the field and reject `off` on the wire.
            canDisableThinking = raw?.optBoolean("canDisableThinking") == true,
        )
    }

    private fun parseBadges(tags: JSONArray?): List<String> {
        if (tags == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until tags.length()) {
            val tag = tags.optString(i)
            if (!tag.lowercase().startsWith(BADGE_PREFIX)) continue
            val label = tag.removePrefix(BADGE_PREFIX).substringBefore(':')
            if (label.isNotEmpty() && label !in result) result += label
        }
        return result
    }
}

/** One catalogue row, already reduced to what a client needs. */
data class ProtoModel(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val defaultContextWindow: Int?,
    val maxInputTokens: Int,
    val supportedContextWindows: List<Int>,
    val maxTokens: Int,
    val supportsImages: Boolean,
    val reasoning: ProtoReasoning,
    val credits: String?,
    val badges: List<String>,
) {
    /** The display rate, with its unit word removed. */
    val rate: String get() = Wire.normalizeCredits(credits) ?: ""

    val multiplier: Double
        get() {
            val bare = rate.removePrefix("x")
            return bare.toDoubleOrNull() ?: -1.0
        }

    val isFree: Boolean get() = multiplier == 0.0
}

/** Reasoning metadata the catalogue declares for one model. */
data class ProtoReasoning(
    val supports: Boolean,
    val onlyReasoning: Boolean,
    val supportedEfforts: List<String>,
    val defaultEffort: String?,
    val canDisableThinking: Boolean,
)


/** Display projection of a catalogue row, keyed to what the UI renders. */
fun ProtoModel.toHubModel(): HubModel = HubModel(
    id = id,
    name = name,
    rate = rate,
    multiplier = multiplier,
    vendor = vendorLabel(),
    supportsImages = supportsImages,
    supportsReasoning = reasoning.supports,
    contextWindow = contextWindow,
    badges = badges,
    // Carried through so the agent page can offer exactly the levels the model
    // accepts rather than a guessed list.
    efforts = reasoning.supportedEfforts,
    defaultEffort = reasoning.defaultEffort,
    canDisableThinking = reasoning.canDisableThinking,
    supportedContextWindows = supportedContextWindows,
)

/**
 * The catalogue declares a one-letter vendor code rather than a brand name, so
 * the letter is expanded for display; an unknown code is shown as-is.
 */
private fun ProtoModel.vendorLabel(): String = when (id.substringBefore('-')) {
    "deepseek" -> "DeepSeek"
    "glm" -> "智谱"
    "kimi" -> "Kimi"
    "minimax" -> "MiniMax"
    "hunyuan", "hy" -> "混元"
    "space" -> "Space"
    else -> ""
}

/** UI-facing model row. */
data class HubModel(
    val id: String,
    val name: String,
    val rate: String = "",
    val multiplier: Double = -1.0,
    val vendor: String = "",
    val supportsImages: Boolean = false,
    val supportsReasoning: Boolean = false,
    val contextWindow: Int = 0,
    val badges: List<String> = emptyList(),
    val efforts: List<String> = emptyList(),
    val defaultEffort: String? = null,
    val canDisableThinking: Boolean = false,
    val supportedContextWindows: List<Int> = emptyList(),
) {
    val isFree: Boolean get() = multiplier == 0.0

    /** Sort key: free first, then cheapest. */
    val sortKey: Double get() = if (multiplier < 0) 999.0 else multiplier

    /** Whether this model accepts a thinking-effort setting at all. */
    val supportsEffort: Boolean get() = efforts.isNotEmpty()

    /**
     * Context lengths worth offering. A model that declares none still has a
     * usable default, so the current window is the only entry.
     */
    val contextOptions: List<Int>
        get() = supportedContextWindows.ifEmpty { listOfNotNull(contextWindow.takeIf { it > 0 }) }

    /** Whether the caller has a real choice of context length. */
    val hasContextChoice: Boolean get() = supportedContextWindows.size > 1
}

/**
 * A WorkBuddy credential.
 *
 * `expiresAt` is epoch **seconds** (the unit the upstream reports and the JWT
 * `exp` claim uses). `enterpriseId` is absent for personal accounts, which is
 * what selects the personal billing endpoint.
 */
data class Credential(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    val domain: String = Wire.CN_CHAT_BASE,
    val uid: String = "",
    val nickname: String = "",
    val enterpriseId: String? = null,
    val source: String = "oauth",
    /**
     * Which saved account this is.
     *
     * Carried so a call can be attributed to the account that served it: the
     * bridge picks an account per request, and usage split by account is only
     * possible if that choice is visible where the call is recorded.
     */
    val accountId: String = "",
) {
    val region: Wire.Region get() = Wire.regionOf(domain)
}
