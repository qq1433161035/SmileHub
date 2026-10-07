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
    data class Message(val role: String, val content: String, val toolCalls: JSONArray? = null, val toolCallId: String = "")

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
        onEvent: (Event) -> Unit,
    ): List<Message> {
        val transcript = history.toMutableList()
        transcript += Message(role = "user", content = prompt)

        var round = 0
        while (round < maxRounds) {
            round++
            val turn = runCatching {
                streamTurn(credential, transcript, modelId, effort, contextWindow, tools, onEvent)
            }.getOrElse { error ->
                Log.e(TAG, "turn failed", error)
                onEvent(Event.Failure(error.message ?: "请求失败"))
                return transcript
            }

            // The assistant turn is appended verbatim, tool calls included:
            // the gateway rejects a follow-up whose history dropped them.
            transcript += Message(
                role = "assistant",
                content = turn.text,
                toolCalls = toolCallsToJson(turn.calls).takeIf { turn.calls.isNotEmpty() },
            )

            if (turn.calls.isEmpty()) {
                onEvent(Event.Done)
                return transcript
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
            }
        }

        onEvent(Event.Failure("达到最大轮次（$maxRounds），已停止"))
        onEvent(Event.Done)
        return transcript
    }

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
        val attempt = Gzip.postChat(region, body) { bytes, compressed ->
            val headers = Wire.chatHeaders(
                region = region,
                accessToken = credential.accessToken,
                uid = credential.uid,
                domain = credential.domain,
                enterpriseId = credential.enterpriseId,
                userAgent = identity.userAgent,
                clientVersion = identity.clientVersion,
            ).toMutableMap()
            if (compressed) headers["Content-Encoding"] = "gzip"
            val opened = open(
                "${Wire.chatBase(region)}/v2/chat/completions",
                headers,
                bytes,
            )
            // Read once, here: the error stream cannot be re-read by whoever
            // reports the failure, and the retry decision needs the same text.
            val status = runCatching { opened.responseCode }.getOrDefault(0)
            val error = if (status in 200..299) "" else runCatching {
                opened.errorStream?.readBytes()?.toString(StandardCharsets.UTF_8).orEmpty()
            }.getOrDefault("")
            Gzip.Attempt(opened, status, error)
        }
        val conn = attempt.value
        if (!attempt.accepted) {
            conn.disconnect()
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

                    collectToolCalls(delta.optJSONArray("tool_calls"), calls, onEvent)
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
        onEvent: (Event) -> Unit,
    ) {
        if (deltas == null) return
        for (i in 0 until deltas.length()) {
            val row = deltas.optJSONObject(i) ?: continue
            val index = row.optInt("index", i)
            val slot = calls.getOrPut(index) { MutableToolCall() }
            row.optString("id").takeIf { it.isNotEmpty() }?.let { slot.id = it }
            val function = row.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isNotEmpty()) {
                if (slot.name.isEmpty()) onEvent(Event.ToolStart(name))
                slot.name = name
            }
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
    ): String {
        val messages = JSONArray()
        for (message in transcript) {
            val row = JSONObject().apply {
                put("role", message.role)
                put("content", message.content)
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
        // The shared shaping rules apply, but which set depends on the build:
        // the international gateway rejects a body without a leading system
        // message (code 11128, surfaced as a security-policy block), so the
        // same branch the bridge takes is taken here.
        return if (credential.region == Wire.Region.GLOBAL) {
            Wire.prepareInternationalChatBody(root.toString())
        } else {
            Wire.prepareChatBody(root.toString())
        }
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
            doOutput = true
            outputStream.use { it.write(body) }
        }

    companion object {
        private const val TAG = "WBHub"
        private const val DEFAULT_MAX_ROUNDS = 12
    }
}
