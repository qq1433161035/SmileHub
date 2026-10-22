package com.wbhub.app.agent

import com.wbhub.app.proto.Gzip

import android.util.Log
import com.wbhub.app.proto.ChatIdentity
import com.wbhub.app.proto.Credential
import com.wbhub.app.proto.Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * The chat loop behind the agent page.
 *
 * Each turn streams one completion; if the model asks for tools, they run and
 * their results go back as a new turn, repeating until the model answers
 * without calling anything or the round limit is reached.
 *
 * The wire format is the same one the bridge proxies, so the request body goes
 * through [Wire.prepareChatBody] and the same identity headers are used. Only
 * the SSE parsing differs: the bridge forwards frames, this has to read them
 * because a tool call arrives split across deltas.
 */
class AgentClient {

    /** One streamed piece of a turn, reported as it arrives. */
    sealed class Event {
        data class Text(val delta: String) : Event()
        data class Reasoning(val delta: String) : Event()
        data class ToolStart(val name: String) : Event()
        data class ToolEnd(val name: String, val ok: Boolean, val summary: String) : Event()

        /**
         * 一轮上下文压缩完成。
         *
         * [removed] 是被摘要掉的消息数，[summaryTokens] 是摘要本身的估算值，
         * [archivePath] 是原文归档文件；[fallback] 为 true 表示摘要请求失败，
         * 只做了硬截断。
         */
        data class Compacted(
            val removed: Int,
            val summaryTokens: Int,
            val archivePath: String?,
            val fallback: Boolean,
        ) : Event()

        data class Failure(val message: String) : Event()

        /**
         * Token and credit totals for one model call.
         *
         * Reported per turn rather than per conversation: a run makes one
         * upstream call per round, and each of those is charged separately, so
         * collapsing them into a single figure would lose what was spent.
         */
        data class Usage(val promptTokens: Int, val completionTokens: Int, val credits: Double) : Event()

        object Done : Event()
    }

    /** One message in the transcript kept for the model. */
    data class Message(
        val role: String,
        val content: String,
        val toolCalls: JSONArray? = null,
        val toolCallId: String = "",
        /** Data URL for the attached image; only used in memory, never persisted. */
        val image: String? = null,
    )

    /** 一次提问结束后的完整状态：发给模型的消息与被压缩后的会话摘要。 */
    data class RunResult(
        val messages: List<Message>,
        val compaction: CompactionState,
    )

    /** A model call and its assistant reply. */
    private data class Turn(val text: String, val calls: List<ToolCall>)
    private data class ToolCall(val id: String, val name: String, val arguments: String)

    /**
     * Runs the whole loop for one user prompt.
     *
     * [history] carries the earlier turns of the conversation so the model sees
     * the same context across prompts. Everything is reported through [onEvent]
     * and the method blocks until the turn finishes, so callers run it off the
     * main thread.
     */
    fun run(
        credential: Credential,
        history: List<Message>,
        prompt: String,
        modelId: String,
        effort: String?,
        tools: AgentTools,
        contextWindow: Int = 0,
        approval: ApprovalMode = ApprovalMode.AUTO,
        /** Blocks until the user answers; runs on the worker thread. */
        ask: (String) -> Boolean = { true },
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        sessionId: String = "",
        initialCompaction: CompactionState = CompactionState(),
        contextEngine: ContextEngine? = null,
        /** Data URL of an image attached to this prompt, if any. */
        promptImage: String? = null,
        onEvent: (Event) -> Unit,
    ): RunResult {
        cancelled = false
        var transcript = history.toMutableList()
        // A failed attempt used to leave its user message in the history, so the
        // next send appended the same prompt again. Reuse a trailing identical
        // prompt instead of duplicating it (this also repairs old sessions).
        val last = transcript.lastOrNull()
        if (last == null || last.role != "user" || last.content != prompt) {
            transcript += Message(role = "user", content = prompt, image = promptImage)
        }
        var compaction = initialCompaction
        var produced = false
        // 中途断流只自动续写一次，避免网络一直抖动时无限循环。
        var continued = false
        // 工具定义每次全量发送，属于固定成本，单独计入预算。
        val fixedTokens = ContextBudget.estimateTokens(JSONArray(tools.definitions).toString())

        var round = 0
        while (round < maxRounds) {
            round++

            if (cancelled) {
                onEvent(Event.Failure("已停止"))
                return RunResult(
                    messages = if (produced) transcript else history,
                    compaction = if (produced) compaction else initialCompaction,
                )
            }

            // 每轮调用模型前治理上下文：旧的工具输出落盘，超预算时摘要最老的轮次。
            // 放在轮次边界，assistant(tool_calls) 与 tool 消息不会被拆开。
            if (contextEngine != null) {
                val step = contextEngine.prepare(
                    messages = transcript,
                    sessionId = sessionId,
                    toolsTokens = fixedTokens,
                    state = compaction,
                    summarize = { summaryPrompt ->
                        requestSummary(credential, modelId, summaryPrompt, onEvent)
                    },
                )
                transcript = step.messages.toMutableList()
                compaction = step.state
                step.compaction?.let {
                    onEvent(Event.Compacted(it.removed, it.summaryTokens, it.archivePath, it.fallback))
                }
            }

            var emitted = false
            val partial = StringBuilder()
            val relay: (Event) -> Unit = { event ->
                if (event is Event.Text) {
                    emitted = true
                    partial.append(event.delta)
                }
                if (event is Event.Reasoning) emitted = true
                onEvent(event)
            }
            var attempt = runCatching {
                streamTurn(credential, transcript, modelId, effort, contextWindow, tools, relay)
            }
            // Status 0 means the response was never readable: no bytes reached the
            // parser, so one silent retry cannot duplicate a billed answer. The same
            // holds for a socket that died before any delta was shown; a mid-stream
            // break is not retried, because the user already saw part of the answer.
            val failure = attempt.exceptionOrNull()
            val retryable = !cancelled && (failure is TransportError ||
                (failure is java.io.IOException && !emitted && !produced))
            if (attempt.isFailure && retryable) {
                Log.w(TAG, "transport error, retrying once", failure)
                Thread.sleep(700)
                attempt = runCatching {
                    streamTurn(credential, transcript, modelId, effort, contextWindow, tools, relay)
                }
            }
            // 断在半路时接上：把已输出的片段当成半条 assistant 消息，再让模型
            // 从中断处续写，避免用户手动打「继续」。只续一次，续写也失败才报错。
            if (attempt.isFailure && !cancelled && !continued &&
                attempt.exceptionOrNull() is java.io.IOException &&
                partial.isNotBlank()
            ) {
                Log.w(TAG, "stream aborted, continuing", attempt.exceptionOrNull())
                transcript += Message(role = "assistant", content = partial.toString())
                transcript += Message(role = "user", content = CONTINUE_PROMPT)
                produced = true
                continued = true
                continue
            }
            val turn = attempt.getOrElse { error ->
                Log.e(TAG, "turn failed", error)
                onEvent(Event.Failure(if (cancelled) "已停止" else describeFailure(error)))
                // Nothing was answered, so this prompt is not part of the history:
                // keeping it would duplicate the message on the next send.
                return RunResult(
                    messages = if (produced) transcript else history,
                    compaction = if (produced) compaction else initialCompaction,
                )
            }

            // The assistant turn is appended verbatim, tool calls included:
            // the gateway rejects a follow-up whose history dropped them.
            transcript += Message(
                role = "assistant",
                content = turn.text,
                toolCalls = toolCallsToJson(turn.calls).takeIf { turn.calls.isNotEmpty() },
            )
            produced = true

            if (turn.calls.isEmpty()) {
                onEvent(Event.Done)
                return RunResult(transcript, compaction)
            }

            // 停止键可能在模型返回工具调用之后才按下：补齐每条调用的结果，
            // 保持 assistant(tool_calls) 与 tool 消息成对，下一轮才能继续用。
            if (cancelled) {
                turn.calls.forEach { call ->
                    transcript += Message(role = "tool", content = "已停止：未执行", toolCallId = call.id)
                }
                onEvent(Event.Failure("已停止"))
                return RunResult(transcript, compaction)
            }

            for (call in turn.calls) {
                onEvent(Event.ToolStart(call.name))
                val args = parseArguments(call.arguments)
                val outcome = tools.run(call.name, args, approval, ask)
                onEvent(Event.ToolEnd(call.name, outcome.ok, outcome.text.lineSequence().firstOrNull().orEmpty()))
                transcript += Message(
                    role = "tool",
                    content = outcome.text,
                    toolCallId = call.id,
                )
                // 工具消息只能带文本，图片另起一条用户消息才能真正传给模型。
                outcome.image?.let { image ->
                    transcript += Message(
                        role = "user",
                        content = "工具 ${call.name} 读取的图片内容如下。",
                        image = image,
                    )
                }
            }
        }

        onEvent(Event.Failure("达到最大轮次（$maxRounds），已停止"))
        onEvent(Event.Done)
        return RunResult(transcript, compaction)
    }

    /** 供“立即压缩”使用的一次性摘要调用。 */
    fun summarize(
        credential: Credential,
        modelId: String,
        prompt: String,
        onEvent: (Event) -> Unit = {},
    ): String? = requestSummary(credential, modelId, prompt, onEvent)

    /**
     * 一次性补全，只用于生成上下文摘要。
     *
     * 网关只接受流式请求，所以这里也走 SSE，只是把文本攒起来、不推给界面；
     * 不设置 tools，模型不会在摘要时发起工具调用。失败返回 null，由压缩器
     * 降级为硬截断。
     */
    private fun requestSummary(
        credential: Credential,
        modelId: String,
        prompt: String,
        onEvent: (Event) -> Unit,
    ): String? = runCatching {
        val body = JSONObject().apply {
            put("model", modelId)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    },
                ),
            )
            put("stream", true)
            put("max_tokens", SUMMARY_MAX_TOKENS)
        }
        val region = credential.region
        val identity = ChatIdentity.forRegion(region)
        val attempt = Gzip.postChat(region, body.toString()) { bytes, compressed ->
            val headers = Wire.chatHeaders(
                region = region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
                userAgent = identity.userAgent,
                clientVersion = identity.clientVersion,
                conversationId = Wire.conversationIdFor(body),
            ).toMutableMap()
            if (compressed) headers["Content-Encoding"] = "gzip"
            val opened = open(
                "${Wire.chatBase(region)}/v2/chat/completions",
                headers,
                bytes,
            )
            val status = runCatching { opened.responseCode }.getOrDefault(0)
            val error = if (status in 200..299) "" else runCatching {
                opened.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            }.getOrDefault("")
            Gzip.Attempt(opened, status, error)
        }
        val conn = attempt.value
        if (!attempt.accepted) {
            conn.disconnect()
            Log.w(TAG, "summary failed: ${attempt.status} ${attempt.errorBody.take(200)}")
            return@runCatching null
        }

        val text = StringBuilder()
        try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                readFrames(reader) { data ->
                    if (data == "[DONE]") return@readFrames false
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@readFrames true
                    chunk.optJSONObject("usage")?.let { usage ->
                        onEvent(
                            Event.Usage(
                                promptTokens = usage.optInt("prompt_tokens", 0),
                                completionTokens = usage.optInt("completion_tokens", 0),
                                credits = usage.optDouble("credit", 0.0),
                            ),
                        )
                    }
                    chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { text.append(it) }
                    true
                }
            }
        } finally {
            conn.disconnect()
        }
        text.toString().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Streams one completion and collects the assistant's text plus any tool
     * calls.
     *
     * Tool arguments arrive as fragments that must be concatenated per index,
     * and the name only appears on the first fragment of each call.
     */
    private fun streamTurn(
        credential: Credential,
        transcript: List<Message>,
        modelId: String,
        effort: String?,
        contextWindow: Int,
        tools: AgentTools,
        onEvent: (Event) -> Unit,
    ): Turn {
        val body = buildBody(transcript, modelId, effort, contextWindow, tools, credential)
        val region = credential.region
        val identity = ChatIdentity.forRegion(region)
        // Compressed for the same reason the bridge does it: the transcript is
        // resent whole on every turn, so it is mostly repetition, and the upload
        // is the largest cost the device pays before the model starts answering.
        // A gateway that will not take it is retried in the clear, and then
        // remembered, so both paths behave the same way.
        val attempt = Gzip.postChat(region, body.toString()) { bytes, compressed ->
            val headers = Wire.chatHeaders(
                region = region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
                userAgent = identity.userAgent,
                clientVersion = identity.clientVersion,
                // One conversation, one id: the transcript is the same
                // conversation across its turns.
                conversationId = Wire.conversationIdFor(body),
            ).toMutableMap()
            if (compressed) headers["Content-Encoding"] = "gzip"
            val opened = open(
                "${Wire.chatBase(region)}/v2/chat/completions",
                headers,
                bytes,
            )
            // Read once, here: the error stream cannot be re-read by whoever
            // reports the failure, and the retry decision needs the same text.
            // A connection that never produced a status is reported as 0 rather
            // than thrown, so Gzip can retry the body in the clear first.
            val status = runCatching { opened.responseCode }.getOrDefault(0)
            val error = if (status in 200..299) "" else runCatching {
                opened.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            }.getOrDefault("")
            Gzip.Attempt(opened, status, error)
        }
        val conn = attempt.value
        if (!attempt.accepted) {
            conn.disconnect()
            if (attempt.status == 0) {
                throw TransportError("网络连接中断，未能读取上游响应")
            }
            val detail = Wire.displayError(attempt.errorBody) ?: attempt.errorBody.take(200)
            throw IllegalStateException("上游返回 ${attempt.status}：$detail")
        }

        val text = StringBuilder()
        val calls = sortedMapOf<Int, MutableToolCall>()
        try {
            conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                readFrames(reader) { data ->
                    if (data == "[DONE]") return@readFrames false
                    val chunk = runCatching { JSONObject(data) }.getOrNull() ?: return@readFrames true

                    // The usage block arrives on its own frame near the end, and
                    // only once per call, so it is read here rather than being
                    // reconstructed from the text.
                    chunk.optJSONObject("usage")?.let { usage ->
                        onEvent(
                            Event.Usage(
                                promptTokens = usage.optInt("prompt_tokens", 0),
                                completionTokens = usage.optInt("completion_tokens", 0),
                                credits = usage.optDouble("credit", 0.0),
                            ),
                        )
                    }

                    val delta = chunk.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta") ?: return@readFrames true

                    val reasoning = delta.optString("reasoning_content")
                    if (reasoning.isNotEmpty()) onEvent(Event.Reasoning(reasoning))

                    val content = delta.optString("content")
                    if (content.isNotEmpty()) {
                        text.append(content)
                        onEvent(Event.Text(content))
                    }

                    collectToolCalls(delta.optJSONArray("tool_calls"), calls)
                    true
                }
            }
        } finally {
            conn.disconnect()
        }

        return Turn(
            text = text.toString(),
            calls = calls.values.map { it.build() }.filter { it.name.isNotEmpty() },
        )
    }

    /** Merges one delta's tool calls into the accumulating per-index map. */
    private fun collectToolCalls(
        deltas: JSONArray?,
        calls: MutableMap<Int, MutableToolCall>,
    ) {
        if (deltas == null) return
        for (i in 0 until deltas.length()) {
            val row = deltas.optJSONObject(i) ?: continue
            val index = row.optInt("index", i)
            val slot = calls.getOrPut(index) { MutableToolCall() }
            row.optString("id").takeIf { it.isNotEmpty() }?.let { slot.id = it }
            val function = row.optJSONObject("function") ?: continue
            // 名字只在这里记录，不在这里发事件：工具真正执行时才发 ToolStart，
            // 否则每调用一次会先出现一条「▶」再出现执行时的一条。
            function.optString("name").takeIf { it.isNotEmpty() }?.let { slot.name = it }
            function.optString("arguments").takeIf { it.isNotEmpty() }?.let { slot.arguments.append(it) }
        }
    }

    private class MutableToolCall {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()

        fun build() = ToolCall(
            id = id.ifEmpty { "call_${System.nanoTime()}" },
            name = name,
            arguments = arguments.toString().ifEmpty { "{}" },
        )
    }

    /**
     * Reads server-sent events, handing each `data:` payload to [onData].
     * Returning false from [onData] stops reading.
     */
    private fun readFrames(reader: BufferedReader, onData: (String) -> Boolean) {
        while (true) {
            val line = reader.readLine() ?: return
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (!onData(data)) return
        }
    }

    /** Builds the request body, dropping the effort key when it does not apply. */
    private fun buildBody(
        transcript: List<Message>,
        modelId: String,
        effort: String?,
        contextWindow: Int,
        tools: AgentTools,
        credential: Credential,
    ): JSONObject {
        val messages = JSONArray()
        for (message in transcript) {
            val row = JSONObject().apply {
                put("role", message.role)
                // 带图的消息用多模态 parts；纯文本仍走字符串，兼容只认字符串的上游。
                put("content", message.image?.let { imageContent(message.content, it) } ?: message.content)
                message.toolCalls?.let { put("tool_calls", it) }
                if (message.toolCallId.isNotEmpty()) put("tool_call_id", message.toolCallId)
            }
            messages.put(row)
        }
        val root = JSONObject().apply {
            put("model", modelId)
            put("messages", messages)
            put("stream", true)
            put("tools", JSONArray(tools.definitions))
            // Let the model decide: forcing a call would make simple questions
            // run a pointless round trip.
            put("tool_choice", "auto")
            if (!effort.isNullOrBlank()) put("reasoning_effort", effort)
            // Only sent when the account actually chose a length; leaving it
            // out lets the model use its own default.
            if (contextWindow > 0) put("context_window", contextWindow)
        }
        // The shared shaping rules apply, and in object form so the caller can
        // still read the body afterwards — the conversation header is derived
        // from it, and re-parsing a serialised copy would be wasted work.
        return if (credential.region == Wire.Region.GLOBAL) {
            Wire.prepareInternationalChatBodyObject(root)
        } else {
            Wire.prepareChatBodyObject(root)
        }
    }

    /** 一条用户消息的多模态 content：文字 + 内联图片（data URL）。 */
    private fun imageContent(text: String, image: String): JSONArray = JSONArray().apply {
        if (text.isNotBlank()) {
            put(JSONObject().put("type", "text").put("text", text))
        }
        put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put("url", image),
            ),
        )
    }

    /** Serialises tool calls back into the shape the gateway expects. */
    private fun toolCallsToJson(calls: List<ToolCall>): JSONArray {
        val array = JSONArray()
        for (call in calls) {
            array.put(
                JSONObject().apply {
                    put("id", call.id)
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", call.name)
                            put("arguments", call.arguments)
                        },
                    )
                },
            )
        }
        return array
    }

    /** Parses tool arguments, tolerating a malformed payload rather than throwing. */
    private fun parseArguments(raw: String): Map<String, Any?> {
        val cleaned = raw.trim().ifEmpty { "{}" }
        val obj = runCatching { JSONObject(cleaned) }.getOrElse {
            Log.w(TAG, "tool arguments were not JSON: $cleaned")
            return emptyMap()
        }
        val result = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = obj.opt(key)
        }
        return result
    }

    private fun open(url: String, headers: Map<String, String>, body: ByteArray): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            // The answer streams token by token, so the read timeout has to
            // outlast a slow model rather than a slow connection.
            readTimeout = 0
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            // 不复用连接：网关会在空闲后单方面关掉 keep-alive，复用旧连接就会
            // 直接报 "Software caused connection abort"。
            setRequestProperty("Connection", "close")
            doOutput = true
            // 停止键要能立刻断开正在读取的流：read 阻塞时只有 disconnect 能解。
            activeConnection = this
            outputStream.use { it.write(body) }
        }

    @Volatile
    private var cancelled = false

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** 用户点了停止：断开在途请求，循环在下一个检查点退出。 */
    fun cancel() {
        cancelled = true
        runCatching { activeConnection?.disconnect() }
        activeConnection = null
    }

    /** 上游从未返回可读响应：连接中断或状态码为 0，可以安全重试一次。 */
    private class TransportError(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /** 把网络异常翻译成能照着处理的提示，而不是把英文的 errno 丢给用户。 */
    private fun describeFailure(error: Throwable): String = when {
        error is java.net.SocketTimeoutException -> "请求超时：网络不稳定，重发一次即可"
        error is java.net.UnknownHostException -> "无法解析服务器地址：检查网络或代理设置"
        error is java.net.SocketException ->
            "连接被中断（${error.message ?: "socket closed"}）：网络切换或代理断开了连接，重发一次即可"
        error is java.io.IOException ->
            "网络异常（${error.message ?: error.javaClass.simpleName}）：重发一次即可"
        error is javax.net.ssl.SSLException -> "TLS 握手失败：检查代理或系统证书，然后重试"
        else -> error.message ?: "请求失败"
    }

    companion object {
        private const val TAG = "WBHub"
        // 一次任务可能需要几十次工具往返（解码、改 smali、构建、签名、安装），
        // 12 轮会在真正的多步任务中途截断；保留一个足够高的上限只为防止死循环。
        private const val DEFAULT_MAX_ROUNDS = 100

        /** 摘要输出上限，压得越低越省额度，但细节也越少。 */
        private const val SUMMARY_MAX_TOKENS = 900

        /** 中途断流后让模型接着写，而不是从头重来。 */
        private const val CONTINUE_PROMPT =
            "（上一条回答因网络中断被截断。请从中断处继续输出剩余内容，" +
                "不要重复已经输出的部分，也不要重新开头。）"
    }
}
