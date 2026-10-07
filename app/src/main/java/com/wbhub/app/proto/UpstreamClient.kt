package com.wbhub.app.proto

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Kotlin port of the upstream client.
 *
 * Each method mirrors one reference implementation call, including the parts
 * that look redundant: the international catalogue uses an App-shaped user
 * agent while CN keeps the CLI one, and enterprise accounts read a different
 * billing endpoint because the personal one serves them an empty list.
 */
class UpstreamClient {

    /** Result of opening a chat stream: either the live connection or a classified failure. */
    sealed class ChatResult {
        data class Ok(val connection: HttpURLConnection, val stream: InputStream) : ChatResult()
        data class Failed(val status: Int, val kind: Wire.ErrorKind, val message: String) : ChatResult()
    }

    /**
     * Opens a chat request. The caller owns the returned connection and must
     * disconnect it; failures carry the classification the bridge maps to a
     * status code.
     */
    fun chatStream(credential: Credential, request: JSONObject, userAgentOverride: String? = null): ChatResult {
        val region = credential.region
        // The caller already holds the request as an object, so the shaping is
        // applied in place. Routing it through a string here would parse and
        // re-serialise a multi-megabyte body for nothing, and that copy is
        // time the client spends waiting for its first token.
        val body = if (region == Wire.Region.GLOBAL) {
            Wire.prepareInternationalChatBodyObject(request)
        } else {
            Wire.prepareChatBodyObject(request)
        }.toString()
        val identity = ChatIdentity.forRegion(region)
        val userAgent = userAgentOverride ?: identity.userAgent
        // Compression, and the recovery when a gateway will not take it, are
        // handled in one place so the endpoint and the built-in chat cannot
        // drift apart on how they reach the same gateway.
        val attempt = Gzip.postChat(region, body) { bytes, compressed ->
            attemptChat(region, credential, identity, userAgent, bytes, compressed)
        }
        return attempt.value
    }

    /**
     * One try at the chat endpoint with the given encoded body.
     *
     * The status and any failure body are read here, once, because the error
     * stream cannot be read twice and both the retry decision and the reported
     * error need them.
     */
    private fun attemptChat(
        region: Wire.Region,
        credential: Credential,
        identity: ChatIdentity,
        userAgent: String,
        body: ByteArray,
        compressed: Boolean,
    ): Gzip.Attempt<ChatResult> {
        val headers = Wire.chatHeaders(
            region = region,
            accessToken = credential.accessToken,
            uid = credential.uid,
            domain = credential.domain,
            enterpriseId = credential.enterpriseId,
            userAgent = userAgent,
            clientVersion = identity.clientVersion,
        ).toMutableMap()
        // Declared only when the body really is compressed, which a body below
        // the threshold is not.
        if (compressed) headers["Content-Encoding"] = "gzip"
        val conn = open(
            "${Wire.chatBase(region)}/v2/chat/completions",
            "POST",
            headers,
            body,
            readTimeoutMs = 0,
        ) ?: return Gzip.Attempt(
            ChatResult.Failed(0, Wire.ErrorKind.SERVER, "transport error"),
            0,
        )

        val status = runCatching { conn.responseCode }.getOrElse {
            conn.disconnect()
            return Gzip.Attempt(
                ChatResult.Failed(0, Wire.ErrorKind.SERVER, "transport error: ${it.message}"),
                0,
            )
        }
        if (status in 200..299) {
            return Gzip.Attempt(ChatResult.Ok(conn, conn.inputStream), status)
        }
        val text = runCatching { conn.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty() }
            .getOrDefault("").take(Wire.ERROR_BODY_LIMIT)
        conn.disconnect()
        return Gzip.Attempt(ChatResult.Failed(status, Wire.classify(status, text), text), status, text)
    }

    /** Exchanges a refresh token for a fresh access token. Throws when refused. */
    fun refreshToken(credential: Credential): Credential {
        val region = credential.region
        val text = request(
            "${Wire.chatBase(region)}/v2/plugin/auth/token/refresh",
            "POST",
            Wire.refreshHeaders(region, credential.refreshToken, credential.enterpriseId),
            "",
        )
        val envelope = Envelope.parse(text)
        if (envelope.code != 0) throw IllegalStateException("refresh refused: ${envelope.msg}")
        val data = envelope.dataOrEmpty()
        val accessToken = data.optString("accessToken")
        if (accessToken.isEmpty()) throw IllegalStateException("refresh returned no access token")
        val expiresIn = data.optInt("expiresIn", 0)
        return credential.copy(
            accessToken = accessToken,
            refreshToken = data.optString("refreshToken").ifEmpty { credential.refreshToken },
            // expiresIn is a duration from now; expiresAt is an absolute instant.
            expiresAt = if (expiresIn > 0) System.currentTimeMillis() / 1000 + expiresIn else credential.expiresAt,
            domain = data.optString("domain").ifEmpty { credential.domain },
        )
    }

    /**
     * Daily check-in. The gateway answers a non-zero business code with a
     * readable message ("already checked in today") rather than an error, so the
     * body is returned rather than thrown.
     */
    fun checkin(credential: Credential): CheckinOutcome {
        val region = credential.region
        val text = request(
            "${Wire.chatBase(region)}/billing/meter/daily-checkin",
            "POST",
            Wire.billingHeaders(region, credential.accessToken, credential.uid, credential.domain, credential.enterpriseId),
            "{}",
        )
        val envelope = Envelope.parse(text)
        return CheckinOutcome(ok = envelope.code == 0, message = envelope.msg.ifEmpty { text.take(120) })
    }

    /** Reads the model catalogue both variants serve from `/v3/config`. */
    fun fetchModels(credential: Credential): List<ProtoModel> {
        val region = credential.region
        val headers = mutableMapOf(
            "Authorization" to "Bearer ${credential.accessToken}",
            "Accept" to "application/json",
            "Origin" to Wire.originOf(region),
            "Referer" to "${Wire.originOf(region)}/",
        )
        headers["User-Agent"] = if (region == Wire.Region.GLOBAL) {
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["X-Product"] = "SaaS"
            ChatIdentity.forRegion(region).appUserAgent
        } else {
            Wire.CLIENT_UA
        }
        val text = request("${Wire.chatBase(region)}/v3/config", "GET", headers, null)
        val envelope = Envelope.parse(text)
        if (envelope.code != 0) throw IllegalStateException("catalog refused: ${envelope.msg}")
        return Wire.parseModels(text)
    }

    /** Aggregated remaining credit. Enterprise accounts read a different endpoint. */
    fun fetchCredits(credential: Credential): Balance {
        return if (credential.region == Wire.Region.CN && !credential.enterpriseId.isNullOrEmpty()) {
            fetchEnterpriseCredits(credential)
        } else {
            fetchPersonalCredits(credential)
        }
    }

    private fun fetchPersonalCredits(credential: Credential): Balance {
        val region = credential.region
        val now = Date()
        val rangeBegin = formatTime(now)
        val rangeEnd = formatTime(Date(now.time + 365L * 101 * 24 * 3600 * 1000))
        val body = JSONObject().apply {
            put("PageNumber", 1)
            put("PageSize", 100)
            put("ProductCode", "p_tcaca")
            put("Status", JSONArray().apply { put(0); put(3) })
            put("PackageEndTimeRangeBegin", rangeBegin)
            put("PackageEndTimeRangeEnd", rangeEnd)
        }.toString()
        val text = request(
            "${Wire.billingBase(region)}/v2/billing/meter/get-user-resource",
            "POST",
            Wire.billingHeaders(region, credential.accessToken, credential.uid, credential.domain, credential.enterpriseId),
            body,
        )
        val envelope = Envelope.parse(text)
        if (envelope.code != 0) throw IllegalStateException("billing refused: ${envelope.msg}")
        val inner = envelope.dataOrEmpty()
            .optJSONObject("Response")
            ?.optJSONObject("Data") ?: JSONObject()
        val accounts = inner.optJSONArray("Accounts") ?: JSONArray()
        val result = mutableListOf<CreditAccount>()
        var total = 0.0
        for (i in 0 until accounts.length()) {
            val account = accounts.optJSONObject(i) ?: continue
            val size = account.optDouble("CycleCapacitySize", 0.0)
            val cycleRemain = account.optDouble("CycleCapacityRemain", 0.0)
            val cycleUsed = account.optDouble("CycleCapacityUsed", 0.0)
            val capacityRemain = account.optDouble("CapacityRemain", 0.0)
            // A positive cycle size means the quota is cyclical and the cycle
            // remainder is the authoritative figure; otherwise the lifetime
            // remainder applies.
            val remain = when {
                size > 0 -> cycleRemain
                cycleRemain > 0 || cycleUsed > 0 -> cycleRemain
                else -> capacityRemain
            }.coerceAtLeast(0.0)
            total += remain
            result += CreditAccount(
                name = account.optString("PackageName").ifEmpty { "(unnamed)" },
                remain = remain,
                size = if (size > 0) size else account.optDouble("CapacitySize", 0.0),
                cycleEnd = account.optString("CycleEndTime"),
            )
        }
        return Balance(total = total, accounts = result, cycleEnd = result.firstOrNull()?.cycleEnd.orEmpty())
    }

    /**
     * Enterprise quota read: one cycle figure rather than a package list.
     * `-1` is the upstream's "no cap" marker, carried as a flag so it can never
     * be mistaken for a balance.
     */
    private fun fetchEnterpriseCredits(credential: Credential): Balance {
        val region = credential.region
        val text = request(
            "${Wire.billingBase(region)}/v2/billing/meter/get-enterprise-user-usage",
            "POST",
            Wire.billingHeaders(region, credential.accessToken, credential.uid, credential.domain, credential.enterpriseId),
            "",
        )
        val envelope = Envelope.parse(text)
        if (envelope.code != 0) throw IllegalStateException("enterprise billing refused: ${envelope.msg}")
        // The official reader accepts the payload at data.data, data, or the
        // envelope itself, so all three are tried.
        val candidates = mutableListOf<JSONObject>()
        runCatching { envelope.dataOrEmpty().optJSONObject("data")?.let { candidates += it } }
        candidates += envelope.dataOrEmpty()
        envelope.document.optJSONObject("data")?.let { candidates += it }
        candidates += envelope.document

        var limit: Double? = null
        var used: Double? = null
        var reset: String? = null
        for (source in candidates) {
            val candidate = when {
                source.has("limitNum") -> source.optDouble("limitNum")
                source.has("limit_num") -> source.optDouble("limit_num")
                else -> continue
            }
            limit = candidate
            used = if (source.has("credit")) source.optDouble("credit") else source.optDouble("used_num")
            reset = source.optString("cycleResetTime").ifEmpty { null }
            break
        }
        // No recognisable quota field is a hard error: reporting 0 would be a
        // confident wrong number rather than a visible failure.
        val resolvedLimit = limit ?: throw IllegalStateException("enterprise billing carried no quota field")
        if (resolvedLimit == -1.0) {
            return Balance(
                total = 0.0,
                accounts = listOf(CreditAccount(name = "企业额度", remain = 0.0, size = 0.0, unlimited = true)),
                unlimited = true,
                cycleEnd = reset.orEmpty(),
            )
        }
        val resolvedUsed = used ?: throw IllegalStateException("enterprise billing carried a limit but no usage")
        val remain = (resolvedLimit - resolvedUsed).coerceAtLeast(0.0)
        return Balance(
            total = remain,
            accounts = listOf(CreditAccount(name = "企业额度", remain = remain, size = resolvedLimit)),
            cycleEnd = reset.orEmpty(),
        )
    }

    // ------------------------------------------------------------------ //
    // Transport
    // ------------------------------------------------------------------ //

    private fun open(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int = Wire.JSON_TIMEOUT_MS,
    ): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            this.readTimeout = readTimeoutMs
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body) }
            }
        }
    }.onFailure { Log.e(TAG, "connect failed: $url", it) }.getOrNull()

    /** Sends a JSON request and returns the body, including non-2xx bodies. */
    private fun request(url: String, method: String, headers: Map<String, String>, body: String?): String {
        val conn = open(url, method, headers, body?.toByteArray(StandardCharsets.UTF_8))
            ?: throw IllegalStateException("transport error connecting to $url")
        return try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun formatTime(date: Date): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)

    private companion object {
        const val TAG = "WBHub"
    }
}

/** Result of a check-in attempt. */
data class CheckinOutcome(val ok: Boolean, val message: String)

/** One package and its remaining credit. */
data class CreditAccount(
    val name: String,
    val remain: Double,
    val size: Double,
    val cycleEnd: String = "",
    val unlimited: Boolean = false,
)

/** Aggregated balance. */
data class Balance(
    val total: Double = 0.0,
    val accounts: List<CreditAccount> = emptyList(),
    val cycleEnd: String = "",
    val unlimited: Boolean = false,
)

/** A response whose payload may sit under `data` or at the top level. */
private class Envelope(val code: Int, val msg: String, val document: JSONObject) {
    fun dataOrEmpty(): JSONObject = document.optJSONObject("data") ?: JSONObject()

    companion object {
        fun parse(text: String): Envelope {
            val document = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("upstream returned non-JSON: ${text.take(160)}")
            }
            return Envelope(
                code = if (document.has("code")) document.optInt("code") else 0,
                msg = document.optString("msg"),
                document = document,
            )
        }
    }
}

/**
 * The identity a chat request presents.
 *
 * The user agent is the shape the official desktop client composes; the app
 * variant is used for the international catalogue, which is split by client
 * identity on the gateway.
 */
data class ChatIdentity(val clientVersion: String, val userAgent: String, val appUserAgent: String) {
    companion object {
        fun forRegion(region: Wire.Region): ChatIdentity =
            if (region == Wire.Region.GLOBAL) {
                ChatIdentity(
                    clientVersion = "5.5.2",
                    userAgent = "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2",
                    appUserAgent = "WorkBuddy/5.5.2",
                )
            } else {
                ChatIdentity(
                    clientVersion = "5.6.2",
                    userAgent = "WorkBuddy/5.6.2 WorkBuddy/5.6.2 CLI/2.63.2",
                    appUserAgent = "WorkBuddy/5.6.2",
                )
            }
    }
}
