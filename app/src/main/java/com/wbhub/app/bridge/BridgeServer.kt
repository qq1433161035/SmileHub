package com.wbhub.app.bridge

import com.wbhub.app.proto.Credential
import com.wbhub.app.proto.UpstreamClient
import com.wbhub.app.proto.Wire
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A small OpenAI-compatible endpoint on loopback.
 *
 * Requests and responses are handled as raw bytes: a request body is declared
 * in bytes and JSON may contain multi-byte characters, so decoding into
 * characters before reading would desynchronise the stream.
 *
 * Only loopback is served and every request must present the shared secret,
 * because the endpoint holds a credential.
 */
class BridgeServer(
    private val port: Int,
    /** Key presented by clients on this device. */
    private val secret: String,
    /** Key presented by peers on the local network. */
    private val lanKey: String,
    private val credential: () -> Credential?,
    private val models: () -> List<String>,
    /** Price multiplier for a model id, or -1 when it is not listed. */
    private val multiplierOf: (String) -> Double = { -1.0 },
    /**
     * Whether to serve the local network as well as this device.
     *
     * Loopback needs no trust decision; anything wider does, because the
     * endpoint holds a credential and a key that a peer can observe travels
     * in clear text over plain HTTP.
     */
    private val lanEnabled: Boolean = false,
    /**
     * Whether a client asking for a single response gets one.
     *
     * The upstream only streams, so such a client is served by collecting the
     * frames here. Off by default: the collection holds the whole answer and
     * delays the first byte until generation ends.
     */
    private val aggregateNonStream: Boolean = false,
    /** Whether the Anthropic Messages shape is served. */
    private val anthropicEnabled: Boolean = false,
    /** Whether the OpenAI Responses shape is served. */
    private val responsesEnabled: Boolean = false,
    /** Whether a client's self-identifying system prompt is rewritten. */
    private val disguiseIdentity: Boolean = false,
    /**
     * Whether the endpoint also speaks TLS.
     *
     * Off by default. Plain HTTP on loopback is already private to the device,
     * and a client that was pointed at `http://` keeps working; turning this on
     * adds a second listener rather than replacing the first, so nothing that
     * already works can break.
     */
    private val httpsEnabled: Boolean = false,
    /** The certificate to present, or a function returning null when unavailable. */
    private val certificate: () -> LocalCertificate.Material? = { null },
    private val onCall: (CallRecord) -> Unit = {},
) {

    /** Host values a loopback-addressed request may carry. */
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "[::1]")

    /** Upper bound on a request body, so one client cannot exhaust memory. */
    private val requestBodyLimit = 64 * 1024 * 1024

    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val client = UpstreamClient()
    private var server: ServerSocket? = null
    private var tlsServer: ServerSocket? = null

    fun start() {
        if (running.getAndSet(true)) return
        // Bound to every interface when the local network is allowed, so the
        // device's own address is reachable; loopback otherwise.
        val bindAddress = if (lanEnabled) "0.0.0.0" else "127.0.0.1"
        val socket = try {
            ServerSocket(port, 64, InetAddress.getByName(bindAddress))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "cannot bind $bindAddress:$port", e)
            running.set(false)
            return
        }
        server = socket
        android.util.Log.i(TAG, "bridge listening on $bindAddress:$port")
        acceptLoop(socket)
        if (httpsEnabled) startTls(bindAddress)
    }

    /**
     * Starts the TLS listener on [port] + 1.
     *
     * A second port rather than a shared one: deciding between TLS and plain
     * text on a connection means reading the first byte and peeking at the TLS
     * handshake record, and a client that connects to the plain port expecting
     * TLS would then get a confusing failure instead of a clear one. Two
     * listeners keep each protocol unambiguous, and the plain one keeps working
     * for everything already configured against it.
     */
    private fun startTls(bindAddress: String) {
        val material = certificate()
        if (material == null) {
            android.util.Log.w(TAG, "HTTPS requested but no certificate is available")
            return
        }
        val socket = try {
            tlsContext(material).serverSocketFactory
                .createServerSocket(port + 1, 64, InetAddress.getByName(bindAddress)) as ServerSocket
        } catch (e: Exception) {
            android.util.Log.e(TAG, "cannot bind TLS on $bindAddress:${port + 1}", e)
            return
        }
        tlsServer = socket
        android.util.Log.i(TAG, "bridge listening on TLS $bindAddress:${port + 1}")
        acceptLoop(socket)
    }

    /** The context a server needs to present [material] and nothing else. */
    private fun tlsContext(material: LocalCertificate.Material): SSLContext {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        // The chain goes in leaf first: a client builds upwards from what it is
        // shown, and the CA at the end is what completes the path.
        store.setKeyEntry(
            "server",
            material.privateKey,
            PASSWORD,
            material.chain,
        )
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, PASSWORD)
        return SSLContext.getInstance("TLS").apply {
            init(keys.keyManagers, null, null)
        }
    }

    private fun acceptLoop(listener: ServerSocket) {
        Thread {
            while (running.get()) {
                val accepted = try {
                    listener.accept()
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    if (running.get()) android.util.Log.w(TAG, "accept failed", e)
                    continue
                }
                pool.submit { handle(accepted) }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        runCatching { tlsServer?.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        try {
            socket.soTimeout = 30_000
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) {
                val buffer = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(buffer, read, length - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read, StandardCharsets.UTF_8)
            } else {
                ""
            }

            BridgeStatus.recordRequest("${requestLine.substringBefore(" ")} ${requestLine.split(" ").getOrNull(1).orEmpty()}")

            val header = headers["authorization"].orEmpty().trim()
            val presented = if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7).trim() else header
            // Which key is expected depends on where the request came from: a
            // peer on the network authenticates with the LAN key, this device
            // with its own. Keeping them apart means a leaked LAN key can be
            // rotated without touching the local clients' configuration.
            val fromLoopback = isLoopbackRequest(socket)
            // Empty for this device: the local path is the assumed one, so a
            // recorded address always means "someone else ran this".
            val sourceIp = if (fromLoopback) "" else remoteAddressOf(socket)
            if (sourceIp.isNotEmpty()) BridgeStatus.recordRemoteRequest()
            val expected = when {
                fromLoopback -> secret
                lanEnabled -> lanKey
                else -> null
            }
            if (expected == null || presented != expected) {
                sendJson(output, 401, """{"error":{"message":"invalid api key","type":"unauthorized"}}""")
                return
            }

            // Inbound hardening. The bind address alone is not a trust
            // boundary: any local process, or a page that re-resolves its own
            // domain to the device's address, can reach the port. A
            // browser-originated request must therefore name a host this server
            // actually answers on, and a body must be JSON.
            //
            // With LAN access on, a peer legitimately addresses the device by
            // its own address, so the check widens from loopback to "an address
            // this device holds" rather than being dropped.
            if (!hostIsAllowed(headers["host"])) {
                sendJson(output, 403, """{"error":{"message":"host not allowed","type":"host_not_allowed"}}""")
                return
            }
            if (!originIsAllowed(headers["origin"])) {
                sendJson(output, 403, """{"error":{"message":"origin not allowed","type":"origin_not_allowed"}}""")
                return
            }
            if (length > requestBodyLimit) {
                sendJson(output, 413, """{"error":{"message":"request too large","type":"too_large"}}""")
                return
            }

            when {
                requestLine.startsWith("GET /healthz") -> {
                    val region = credential()?.region?.name ?: "none"
                    BridgeStatus.region = region
                    sendJson(output, 200, """{"ok":true,"region":"$region"}""")
                }
                requestLine.startsWith("GET /v1/models") -> {
                    val list = models().joinToString(",") {
                        """{"id":"$it","object":"model","owned_by":"workbuddy"}"""
                    }
                    sendJson(output, 200, """{"object":"list","data":[$list]}""")
                }
                requestLine.startsWith("POST /v1/chat/completions") ->
                    chat(output, body, headers["content-type"], sourceIp, ApiFormat.OPENAI)
                requestLine.startsWith("POST /v1/messages") ->
                    chat(output, body, headers["content-type"], sourceIp, ApiFormat.ANTHROPIC)
                requestLine.startsWith("POST /v1/responses") ->
                    chat(output, body, headers["content-type"], sourceIp, ApiFormat.RESPONSES)
                else -> sendJson(output, 404, """{"error":{"message":"not found","type":"not_found"}}""")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "request failed", e)
            runCatching { sendJson(output, 500, """{"error":{"message":"internal"}}""") }
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Whether a dialect is open for use.
     *
     * Chat Completions is what the upstream speaks, so it is always available;
     * the other two are translations and are opt-in.
     */
    private fun formatEnabled(format: ApiFormat): Boolean = when (format) {
        ApiFormat.OPENAI -> true
        ApiFormat.ANTHROPIC -> anthropicEnabled
        ApiFormat.RESPONSES -> responsesEnabled
    }

    /** Reads one CRLF-terminated line as bytes; the request head is ASCII. */
    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (b == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(b.toChar())
        }
    }

    private fun chat(
        output: OutputStream,
        body: String,
        contentType: String?,
        sourceIp: String,
        format: ApiFormat,
    ) {
        // The upstream rejects non-JSON, and allowing form posts would let a
        // simple cross-site request drive a paid model call.
        if (contentType == null || !contentType.trim().lowercase().startsWith("application/json")) {
            sendJson(output, 415, """{"error":{"message":"Content-Type must be application/json","type":"unsupported_media_type"}}""")
            return
        }
        val cred = credential()
        if (cred == null) {
            sendJson(output, 401, """{"error":{"message":"no credential","type":"not_signed_in"}}""")
            return
        }
        // Parsed once and kept as an object from here on. Every later stage —
        // the dialect conversion, the shaping the gateway needs, the disguise —
        // edits this same object, so a request is never serialised only to be
        // parsed again. On the multi-megabyte bodies a coding client sends,
        // those round trips are the largest cost the endpoint imposes before
        // the upstream has seen anything.
        val request = runCatching { JSONObject(body) }.getOrNull()
        if (request == null) {
            sendJson(output, 400, """{"error":{"message":"invalid json"}}""")
            return
        }
        val model = request.optString("model")
        // Read before the body is rewritten: prepareChatBody forces stream:true
        // for the upstream, which would lose what the client actually asked for.
        val wantsStream = ApiAdapters.wantsStream(request, format)
        // Whether this dialect may be used at all is a setting, because each one
        // widens what a caller can reach.
        if (!formatEnabled(format)) {
            sendJson(output, 404, """{"error":{"message":"not found","type":"not_found"}}""")
            return
        }
        // A request for a single response is only honoured when the feature is
        // on; otherwise the stream is passed through as before, which is what a
        // client parsing SSE expects anyway.
        val serveWholeResponse = !wantsStream && aggregateNonStream
        val started = System.currentTimeMillis()
        val upstreamRequest = ApiAdapters.toChatBody(request, format, disguiseIdentity)
        when (val result = client.chatStream(cred, upstreamRequest)) {
            is UpstreamClient.ChatResult.Failed -> {
                val detail = Wire.displayError(result.message) ?: result.message.take(200)
                BridgeStatus.recordError("${result.kind.name.lowercase()}: $detail")
                // A 401 keeps its status note so the caller can classify it as
                // auth; every other failure speaks through the message alone,
                // because a bare status word would mask a business error.
                val note = if (result.status == 401) " (http 401)" else ""
                onCall(
                    CallRecord(
                        timestamp = started,
                        model = model,
                        outcome = CallRecord.Outcome.FAILED,
                        detail = "${result.kind.name.lowercase()}: $detail".take(120),
                        sourceIp = sourceIp,
                        accountId = cred.accountId,
                        accountLabel = cred.nickname,
                        multiplier = multiplierOf(model),
                    ),
                )
                sendJson(
                    output,
                    KIND_STATUS.getValue(result.kind),
                    """{"error":{"message":"workbuddy ${result.kind.name.lowercase()}$note: ${detail.replace("\"", "'")}","type":"${result.kind.name.lowercase()}"}}""",
                )
            }
            is UpstreamClient.ChatResult.Ok -> {
                try {
                    // The upstream only speaks SSE (it rejects stream:false with
                    // code 11101), so a client asking for a single response is
                    // served by aggregating that stream here rather than by
                    // asking the upstream for something it will not do.
                    val usage = if (serveWholeResponse) {
                        aggregate(output, result, model, format)
                    } else {
                        relay(output, result, model, format)
                    }
                    onCall(
                        CallRecord(
                            timestamp = started,
                            model = model,
                            outcome = CallRecord.Outcome.OK,
                            promptTokens = usage.prompt,
                            completionTokens = usage.completion,
                            credits = usage.credits,
                            sourceIp = sourceIp,
                            accountId = cred.accountId,
                            accountLabel = cred.nickname,
                            multiplier = multiplierOf(model),
                        ),
                    )
                } finally {
                    runCatching { result.connection.disconnect() }
                }
            }
        }
    }

    /**
     * Reads the upstream stream and answers with one JSON body.
     *
     * The upstream has no non-streaming mode (it refuses `stream:false` with
     * code 11101), so a client that asked for a single response is served by
     * collecting the frames here. The deltas are concatenated in arrival order
     * and the result is shaped like a normal completion, which is what such a
     * client parses.
     */
    private fun aggregate(
        output: OutputStream,
        result: UpstreamClient.ChatResult.Ok,
        model: String,
        format: ApiFormat,
    ): SseUsage {
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = JSONArray()
        var usage = SseUsage()
        val buffer = ByteArray(4096)
        val tail = StringBuilder()
        // Frames arrive split across reads, so a carry holds an incomplete line
        // until the rest of it shows up.
        var carry = ""
        try {
            while (true) {
                val read = result.stream.read(buffer)
                if (read < 0) break
                tail.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                if (tail.length > USAGE_SCAN_BYTES) {
                    tail.delete(0, tail.length - USAGE_SCAN_BYTES)
                }
                usage = parseUsage(tail.toString()) ?: usage

                carry += String(buffer, 0, read, StandardCharsets.UTF_8)
                val lines = carry.split("\n")
                carry = lines.last()
                for (line in lines.dropLast(1)) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val chunk = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta") ?: continue
                    delta.optString("content").takeIf { it.isNotEmpty() }?.let { content.append(it) }
                    delta.optString("reasoning_content").takeIf { it.isNotEmpty() }
                        ?.let { reasoning.append(it) }
                    delta.optJSONArray("tool_calls")?.let { mergeToolCalls(calls, it) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "chat aggregation interrupted", e)
        }

        val message = JSONObject().apply {
            put("role", "assistant")
            put("content", content.toString())
            if (reasoning.isNotEmpty()) put("reasoning_content", reasoning.toString())
            if (calls.length() > 0) put("tool_calls", calls)
        }
        val payload = JSONObject().apply {
            put("id", "chatcmpl-" + System.currentTimeMillis())
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", model)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", message)
                // Tool calls end a turn the same way they do when streamed.
                put("finish_reason", if (calls.length() > 0) "tool_calls" else "stop")
            }))
            put("usage", JSONObject().apply {
                put("prompt_tokens", usage.prompt)
                put("completion_tokens", usage.completion)
                put("total_tokens", usage.prompt + usage.completion)
            })
        }
        // A translated dialect reshapes the whole body at once, which is
        // simpler than mapping it field by field as it is assembled.
        val answer = ApiAdapters.fromChatResponse(payload, format, model)
        sendJson(output, 200, answer.toString())
        return usage
    }

    /**
     * Folds one frame's tool-call fragments into the accumulated set.
     *
     * Arguments arrive split across frames and are keyed by index, so a call
     * accumulates its string until the stream ends; the name only appears on
     * the first fragment.
     */
    private fun mergeToolCalls(sink: JSONArray, deltas: JSONArray) {
        for (i in 0 until deltas.length()) {
            val row = deltas.optJSONObject(i) ?: continue
            val index = row.optInt("index", i)
            while (sink.length() <= index) {
                sink.put(JSONObject().apply {
                    put("id", "")
                    put("type", "function")
                    put("function", JSONObject().apply { put("name", ""); put("arguments", "") })
                })
            }
            val target = sink.optJSONObject(index) ?: continue
            val fn = target.optJSONObject("function") ?: continue
            row.optString("id").takeIf { it.isNotEmpty() }?.let { target.put("id", it) }
            val incoming = row.optJSONObject("function") ?: continue
            incoming.optString("name").takeIf { it.isNotEmpty() }?.let { fn.put("name", it) }
            fn.put("arguments", fn.optString("arguments") + incoming.optString("arguments"))
        }
    }

    /**
     * Streams the upstream answer through unchanged.
     *
     * The stream stays open for the whole generation, so it is forwarded frame
     * by frame — buffering it would stall the caller until the answer finished.
     */
    private fun relay(
        output: OutputStream,
        result: UpstreamClient.ChatResult.Ok,
        model: String,
        format: ApiFormat,
    ): SseUsage {
        output.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/event-stream; charset=utf-8\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.US_ASCII),
        )
        output.flush()

        // Chat Completions is what the upstream already speaks, so those frames
        // go out untouched. A translated dialect has to be read and re-emitted,
        // which costs the byte-for-byte pass-through but keeps the stream
        // incremental.
        if (format == ApiFormat.OPENAI) return relayRaw(output, result)

        val adapter = ApiStreamAdapter(format, model)
        adapter.opening().forEach { output.write(it.toByteArray(StandardCharsets.UTF_8)) }
        output.flush()

        val buffer = ByteArray(4096)
        val tail = StringBuilder()
        var carry = ""
        var usage = SseUsage()
        var finishReason: String? = null
        try {
            while (true) {
                val read = result.stream.read(buffer)
                if (read < 0) break
                val chunk = String(buffer, 0, read, StandardCharsets.UTF_8)
                tail.append(chunk)
                if (tail.length > USAGE_SCAN_BYTES) {
                    tail.delete(0, tail.length - USAGE_SCAN_BYTES)
                }
                usage = parseUsage(tail.toString()) ?: usage

                // Frames arrive split across reads, so an incomplete line is
                // held back until the rest of it shows up.
                carry += chunk
                val lines = carry.split("\n")
                carry = lines.last()
                for (line in lines.dropLast(1)) {
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("data:")) continue
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue
                    val frame = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val choice = frame.optJSONArray("choices")?.optJSONObject(0) ?: continue
                    choice.optString("finish_reason").takeIf { it.isNotEmpty() }?.let { finishReason = it }
                    val delta = choice.optJSONObject("delta") ?: continue
                    adapter.onDelta(delta).forEach {
                        output.write(it.toByteArray(StandardCharsets.UTF_8))
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "chat relay interrupted", e)
        }
        adapter.closing(finishReason, usage.prompt, usage.completion).forEach {
            output.write(it.toByteArray(StandardCharsets.UTF_8))
        }
        output.flush()
        return usage
    }

    /**
     * Forwards the upstream stream unchanged.
     *
     * The bytes the client receives are the ones the upstream sent, so the only
     * work here is scanning for the usage block that reports what was billed.
     */
    private fun relayRaw(output: OutputStream, result: UpstreamClient.ChatResult.Ok): SseUsage {
        val buffer = ByteArray(4096)
        val tail = StringBuilder()
        var usage = SseUsage()
        try {
            while (true) {
                val read = result.stream.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                output.flush()
                tail.append(String(buffer, 0, read, StandardCharsets.UTF_8))
                if (tail.length > USAGE_SCAN_BYTES) {
                    tail.delete(0, tail.length - USAGE_SCAN_BYTES)
                }
                usage = parseUsage(tail.toString()) ?: usage
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "chat relay interrupted", e)
        }
        return usage
    }

    /** Token and credit figures reported by the upstream for one call. */
    private data class SseUsage(
        val prompt: Int = 0,
        val completion: Int = 0,
        val credits: Double = 0.0,
    )

    /**
     * Reads the newest usage block out of the accumulated stream tail.
     *
     * Scanned rather than accumulated per frame because the block is a plain
     * JSON object inside SSE data lines, and only the final one carries the
     * totals.
     */
    private fun parseUsage(text: String): SseUsage? {
        val index = text.lastIndexOf("\"usage\"")
        if (index < 0) return null
        val open = text.indexOf('{', index)
        if (open < 0) return null
        // The usage object is nested (it carries a token-details member), so the
        // end is found by brace depth rather than by the first closing brace.
        var depth = 0
        var end = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        if (end < 0) return null
        return runCatching {
            val usage = JSONObject(text.substring(open, end + 1))
            SseUsage(
                prompt = usage.optInt("prompt_tokens", 0),
                completion = usage.optInt("completion_tokens", 0),
                credits = usage.optDouble("credit", 0.0),
            )
        }.getOrNull()
    }

    private fun sendJson(output: OutputStream, code: Int, payload: String) {
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val head = (
            "HTTP/1.1 $code ${statusText(code)}\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.US_ASCII)
        runCatching {
            output.write(head)
            output.write(bytes)
            output.flush()
        }
    }

    /** Strips an optional port, IPv6-bracket aware. */
    private fun hostnameOf(host: String): String {
        var name = host.trim().lowercase()
        if (name.startsWith("[")) {
            val end = name.indexOf(']')
            return if (end == -1) name else name.substring(0, end + 1)
        }
        val colon = name.lastIndexOf(':')
        if (colon != -1 && !name.substring(0, colon).contains(":") &&
            name.substring(colon + 1).all { it.isDigit() }
        ) {
            name = name.substring(0, colon)
        }
        return name
    }

    /** Whether the accepted connection came from this device. */
    private fun isLoopbackRequest(socket: Socket): Boolean = runCatching {
        socket.inetAddress?.isLoopbackAddress ?: false
    }.getOrDefault(false)

    /** The peer's address, unmapped from its IPv4-in-IPv6 form when needed. */
    private fun remoteAddressOf(socket: Socket): String = runCatching {
        val host = socket.inetAddress?.hostAddress.orEmpty()
        // A dual-stack accept reports an IPv4 peer as ::ffff:a.b.c.d, which is
        // noise in a log line.
        host.removePrefix("::ffff:")
    }.getOrDefault("")

    /**
     * Whether a Host header names something this server answers on.
     *
     * Loopback always passes. With LAN access on, a peer addresses the device
     * by an address it actually holds or by a name resolving to one, so those
     * pass too; an arbitrary name still does not.
     */
    private fun hostIsAllowed(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val name = hostnameOf(host).lowercase()
        if (name in loopbackHosts) return true
        if (!lanEnabled) return false
        // A private address in the header is the normal case for a peer on the
        // same network; anything else would be a name this device does not own.
        return isPrivateAddress(name)
    }

    /**
     * Whether a browser-sent Origin is allowed; an absent Origin passes.
     *
     * A browser only sends this for cross-origin calls, so its presence means
     * a page is driving the request and it must be a page on this device or, in
     * LAN mode, on a peer that could legitimately call the endpoint.
     */
    private fun originIsAllowed(origin: String?): Boolean {
        if (origin.isNullOrBlank()) return true
        return runCatching {
            val host = java.net.URI(origin).host?.lowercase()
            when {
                host == null -> false
                host in loopbackHosts || host == "::1" -> true
                lanEnabled -> isPrivateAddress(host)
                else -> false
            }
        }.getOrDefault(false)
    }

    /**
     * Whether an address is inside a private range.
     *
     * The endpoint is only meant for the local network, so the check is the
     * private blocks rather than "any address": a public name resolving here
     * would mean the request came from somewhere it should not have.
     */
    private fun isPrivateAddress(host: String): Boolean {
        if (host == "::1") return true
        val parts = host.split(".")
        if (parts.size != 4) {
            // A hostname rather than a literal: accept it only if it resolves
            // into a private range, which covers the "phone.local" case.
            return runCatching {
                InetAddress.getAllByName(host).any { isPrivateAddress(it.hostAddress.orEmpty()) }
            }.getOrDefault(false)
        }
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            // 172.16.0.0 - 172.31.255.255
            octets[0] == 172 && octets[1] in 16..31 -> true
            // Link-local, the range Android hands out for hotspots.
            octets[0] == 169 && octets[1] == 254 -> true
            else -> false
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        403 -> "Forbidden"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        429 -> "Too Many Requests"
        else -> "OK"
    }

    private companion object {
        const val TAG = "WBHub"

        /** Password guarding the in-memory key store; it never reaches disk. */
        val PASSWORD = "wb-hub".toCharArray()

        /** How much of the stream tail is kept for usage scanning. */
        const val USAGE_SCAN_BYTES = 8192

        /** HTTP status each failure class surfaces as. */
        val KIND_STATUS = mapOf(
            Wire.ErrorKind.HARD_CREDIT to 402,
            Wire.ErrorKind.SOFT_RATE to 429,
            Wire.ErrorKind.SESSION_DEAD to 401,
            Wire.ErrorKind.NOT_FOUND to 502,
            Wire.ErrorKind.SERVER to 502,
            Wire.ErrorKind.CLIENT to 400,
        )
    }
}
