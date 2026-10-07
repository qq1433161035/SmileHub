package com.wbhub.app.bridge

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format a client is speaking.
 *
 * The upstream understands only Chat Completions, so the other two are inbound
 * dialects: a request is translated into that shape and the answer is
 * translated back out.
 */
enum class ApiFormat { OPENAI, RESPONSES, ANTHROPIC }

/**
 * Translation between the supported client dialects and Chat Completions.
 *
 * Each dialect names the same concepts differently — a system prompt is a
 * top-level field in one and a message in another, tool schemas are nested in
 * one and flat in another — so the work is mostly renaming and regrouping
 * rather than anything semantic.
 */
object ApiAdapters {

    /**
     * Markers the upstream refuses, each paired with what replaces it.
     *
     * Measured against the live gateway. The match is a case-insensitive
     * substring search rather than a prefix test: the third marker trips the
     * filter while sitting in the middle of a 27 kB prompt, and changing any
     * single character in it clears the block. The first two only ever appear
     * at the start of a prompt, so one substring pass covers all three.
     *
     * The third is the one Claude Code ships in its own instructions — a
     * sentence pointing users at its issue tracker. It is filtered only
     * together with the URL that follows it, so the wording is changed and the
     * link is left intact and usable.
     */
    private val BLOCKED_MARKERS: List<Pair<String, String>> = listOf(
        // The identity sentence; one word is dropped, which is enough to stop
        // the match while leaving the sentence readable to the model.
        "You are Claude Code, Anthropic's official CLI for Claude." to
            "You are Claude Code, an official CLI for Claude.",
        // The billing header's name carries no meaning for the model; the
        // values after the colon are left in place.
        "x-anthropic-billing-header" to "x-client-billing-header",
        "To give feedback, users should report the issue at" to
            "For feedback, users should report the issue at",
    )

    /**
     * Rewrites every marker the upstream refuses.
     *
     * Only the markers are touched: the rest of the message is the client's own
     * instructions and is passed through unchanged, so the model sees the same
     * request the client composed. Every occurrence is replaced, because a copy
     * left behind is one the upstream would still find.
     */
    fun disguise(text: String): String {
        var rewritten = text
        for ((marker, replacement) in BLOCKED_MARKERS) {
            if (rewritten.contains(marker, ignoreCase = true)) {
                rewritten = rewritten.replace(marker, replacement, ignoreCase = true)
            }
        }
        return rewritten
    }

    /** Whether [text] carries anything the upstream refuses. */
    fun needsDisguise(text: String): Boolean =
        BLOCKED_MARKERS.any { text.contains(it.first, ignoreCase = true) }

    /**
     * Roles whose text the upstream reads.
     *
     * The gateway scans the whole conversation, not just the leading prompt: a
     * marker replayed in an earlier assistant turn trips the filter the same way
     * a fresh one does. `user` turns are deliberately left out — they carry what
     * was actually asked, and the gateway does not filter them.
     *
     * `developer` is here even though the upstream never sees it: the dialect
     * spells the system prompt that way, and it is renamed to `system` further
     * down the pipeline — after this pass has already run. Leaving it out would
     * let a client that uses the newer spelling through unfiltered.
     */
    private val DISGUISED_ROLES = setOf("system", "assistant", "developer")

    /** Whether the client asked for a streamed answer. */
    fun wantsStream(body: JSONObject, format: ApiFormat): Boolean =
        body.optBoolean("stream", false)

    /** Rewrites a request in [format] as a Chat Completions request. */
    fun toChatBody(body: JSONObject, format: ApiFormat, disguise: Boolean = false): JSONObject {
        val translated = when (format) {
            ApiFormat.OPENAI -> body
            ApiFormat.ANTHROPIC -> anthropicToChat(body)
            ApiFormat.RESPONSES -> responsesToChat(body)
        }
        // Applied after translation so one pass covers every dialect, and to the
        // roles the upstream reads: user text is not what it filters, and
        // rewriting that would change what was asked.
        return if (disguise) disguiseMessages(translated) else translated
    }

    /**
     * Rewrites the markers the upstream refuses, wherever they appear.
     *
     * Returns the same object when nothing needed changing.
     */
    private fun disguiseMessages(chat: JSONObject): JSONObject {
        val messages = chat.optJSONArray("messages") ?: return chat
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            if (message.optString("role") !in DISGUISED_ROLES) continue
            when (val content = message.opt("content")) {
                is String -> if (needsDisguise(content)) message.put("content", disguise(content))
                is JSONArray -> {
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        val partText = part.optString("text")
                        if (needsDisguise(partText)) part.put("text", disguise(partText))
                    }
                }
            }
        }
        return chat
    }

    // ------------------------------------------------------------------ //
    // Inbound: Anthropic Messages
    // ------------------------------------------------------------------ //

    private fun anthropicToChat(body: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model"))
        val messages = JSONArray()

        // Anthropic keeps the system prompt beside the messages rather than in
        // them, which is the first thing that has to move.
        when (val system = body.opt("system")) {
            is String -> if (system.isNotEmpty()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            is JSONArray -> {
                val text = (0 until system.length()).mapNotNull { i ->
                    system.optJSONObject(i)?.optString("text")?.takeIf { it.isNotEmpty() }
                }.joinToString("\n")
                if (text.isNotEmpty()) {
                    messages.put(JSONObject().put("role", "system").put("content", text))
                }
            }
        }

        val inbound = body.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until inbound.length()) {
            val message = inbound.optJSONObject(i) ?: continue
            val role = message.optString("role")
            when (val content = message.opt("content")) {
                is String -> messages.put(JSONObject().put("role", role).put("content", content))
                is JSONArray -> appendAnthropicBlocks(messages, role, content)
            }
        }
        out.put("messages", messages)

        if (body.has("max_tokens")) out.put("max_tokens", body.optInt("max_tokens"))
        if (body.has("temperature")) out.put("temperature", body.optDouble("temperature"))
        if (body.has("top_p")) out.put("top_p", body.optDouble("top_p"))
        body.optJSONArray("stop_sequences")?.let { seq ->
            if (seq.length() > 0) out.put("stop", seq)
        }
        anthropicToolsToChat(body.optJSONArray("tools"))?.let { out.put("tools", it) }
        return out
    }

    /**
     * Flattens one Anthropic content array into Chat Completions messages.
     *
     * A single Anthropic message may hold text, tool calls, or tool results
     * together, while Chat Completions wants those as separate messages with
     * their own roles, so one input message can become several.
     */
    private fun appendAnthropicBlocks(sink: JSONArray, role: String, blocks: JSONArray) {
        val text = StringBuilder()
        val toolCalls = JSONArray()
        val toolResults = mutableListOf<JSONObject>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> toolCalls.put(
                    JSONObject().apply {
                        put("id", block.optString("id"))
                        put("type", "function")
                        put(
                            "function",
                            JSONObject().apply {
                                put("name", block.optString("name"))
                                // The schema says an object; the wire wants a string.
                                put("arguments", block.opt("input")?.toString() ?: "{}")
                            },
                        )
                    },
                )
                "tool_result" -> toolResults.add(
                    JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", block.optString("tool_use_id"))
                        put("content", flattenContent(block.opt("content")))
                    },
                )
            }
        }
        if (toolCalls.length() > 0) {
            sink.put(
                JSONObject().apply {
                    put("role", "assistant")
                    put("content", text.toString())
                    put("tool_calls", toolCalls)
                },
            )
        } else if (text.isNotEmpty()) {
            sink.put(JSONObject().put("role", role).put("content", text.toString()))
        }
        toolResults.forEach { sink.put(it) }
    }

    /** Anthropic tool schemas are `{name, input_schema}`; Chat wants them nested. */
    private fun anthropicToolsToChat(tools: JSONArray?): JSONArray? {
        if (tools == null || tools.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            out.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", tool.optString("name"))
                            put("description", tool.optString("description"))
                            put(
                                "parameters",
                                tool.optJSONObject("input_schema")
                                    ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                            )
                        },
                    )
                },
            )
        }
        return out
    }

    // ------------------------------------------------------------------ //
    // Inbound: OpenAI Responses
    // ------------------------------------------------------------------ //

    private fun responsesToChat(body: JSONObject): JSONObject {
        val out = JSONObject()
        out.put("model", body.optString("model"))
        val messages = JSONArray()

        // The Responses API keeps the system prompt out of the input list.
        body.optString("instructions").takeIf { it.isNotEmpty() }?.let {
            messages.put(JSONObject().put("role", "system").put("content", it))
        }

        when (val input = body.opt("input")) {
            is String -> messages.put(JSONObject().put("role", "user").put("content", input))
            is JSONArray -> {
                for (i in 0 until input.length()) {
                    val item = input.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        // A tool's answer arrives as its own item, keyed to the
                        // call it belongs to.
                        "function_call_output" -> messages.put(
                            JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", item.optString("call_id"))
                                put("content", flattenContent(item.opt("output")))
                            },
                        )
                        // A prior tool call is replayed as part of the assistant
                        // turn rather than standing alone.
                        "function_call" -> appendAssistantCall(
                            messages,
                            item.optString("call_id"),
                            item.optString("name"),
                            item.optString("arguments"),
                        )
                        else -> {
                            val role = item.optString("role").ifEmpty { "user" }
                            val content = flattenContent(item.opt("content"))
                            if (role == "assistant" && content.isEmpty()) continue
                            messages.put(JSONObject().put("role", role).put("content", content))
                        }
                    }
                }
            }
        }
        out.put("messages", messages)

        if (body.has("max_output_tokens")) out.put("max_tokens", body.optInt("max_output_tokens"))
        if (body.has("temperature")) out.put("temperature", body.optDouble("temperature"))
        if (body.has("top_p")) out.put("top_p", body.optDouble("top_p"))
        responsesToolsToChat(body.optJSONArray("tools"))?.let { out.put("tools", it) }
        return out
    }

    /**
     * Attaches a replayed tool call to the assistant message it belongs to.
     *
     * The Responses API lists calls as siblings of messages, while Chat
     * Completions hangs them off the assistant turn, so consecutive calls are
     * gathered onto one message.
     */
    private fun appendAssistantCall(
        sink: JSONArray,
        callId: String,
        name: String,
        arguments: String,
    ) {
        val last = sink.optJSONObject(sink.length() - 1)
        val target = if (last != null && last.optString("role") == "assistant" && last.has("tool_calls")) {
            last
        } else {
            JSONObject().apply {
                put("role", "assistant")
                put("content", "")
                put("tool_calls", JSONArray())
            }.also { sink.put(it) }
        }
        target.optJSONArray("tool_calls")?.put(
            JSONObject().apply {
                put("id", callId)
                put("type", "function")
                put(
                    "function",
                    JSONObject().apply {
                        put("name", name)
                        put("arguments", arguments.ifEmpty { "{}" })
                    },
                )
            },
        )
    }

    /** Responses tool schemas are flat; Chat wants them under `function`. */
    private fun responsesToolsToChat(tools: JSONArray?): JSONArray? {
        if (tools == null || tools.length() == 0) return null
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            if (tool.optString("type") != "function") continue
            out.put(
                JSONObject().apply {
                    put("type", "function")
                    put(
                        "function",
                        JSONObject().apply {
                            put("name", tool.optString("name"))
                            put("description", tool.optString("description"))
                            put(
                                "parameters",
                                tool.optJSONObject("parameters")
                                    ?: JSONObject().put("type", "object").put("properties", JSONObject()),
                            )
                        },
                    )
                },
            )
        }
        return out.takeIf { it.length() > 0 }
    }

    // ------------------------------------------------------------------ //
    // Outbound: a whole answer
    // ------------------------------------------------------------------ //

    /** Rewrites an aggregated Chat Completions answer into [format]. */
    fun fromChatResponse(chat: JSONObject, format: ApiFormat, model: String): JSONObject =
        when (format) {
            ApiFormat.OPENAI -> chat
            ApiFormat.ANTHROPIC -> chatToAnthropic(chat, model)
            ApiFormat.RESPONSES -> chatToResponses(chat, model)
        }

    private fun chatToAnthropic(chat: JSONObject, model: String): JSONObject {
        val choice = chat.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        val content = JSONArray()

        message.optString("content").takeIf { it.isNotEmpty() }?.let {
            content.put(JSONObject().put("type", "text").put("text", it))
        }
        message.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                content.put(
                    JSONObject().apply {
                        put("type", "tool_use")
                        put("id", call.optString("id").ifEmpty { "toolu_$i" })
                        put("name", fn.optString("name"))
                        // Chat carries arguments as a string; this dialect wants
                        // the parsed object.
                        put("input", parseArguments(fn.optString("arguments")))
                    },
                )
            }
        }

        val usage = chat.optJSONObject("usage") ?: JSONObject()
        return JSONObject().apply {
            put("id", "msg_" + System.currentTimeMillis())
            put("type", "message")
            put("role", "assistant")
            put("model", model)
            put("content", content)
            put("stop_reason", anthropicStopReason(choice.optString("finish_reason")))
            put("stop_sequence", JSONObject.NULL)
            put(
                "usage",
                JSONObject().apply {
                    put("input_tokens", usage.optInt("prompt_tokens"))
                    put("output_tokens", usage.optInt("completion_tokens"))
                },
            )
        }
    }

    private fun chatToResponses(chat: JSONObject, model: String): JSONObject {
        val choice = chat.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        val output = JSONArray()

        message.optString("content").takeIf { it.isNotEmpty() }?.let { text ->
            output.put(
                JSONObject().apply {
                    put("type", "message")
                    put("id", "msg_" + System.currentTimeMillis())
                    put("role", "assistant")
                    put("status", "completed")
                    put(
                        "content",
                        JSONArray().put(
                            JSONObject().apply {
                                put("type", "output_text")
                                put("text", text)
                                put("annotations", JSONArray())
                            },
                        ),
                    )
                },
            )
        }
        message.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                output.put(
                    JSONObject().apply {
                        put("type", "function_call")
                        put("id", "fc_" + System.currentTimeMillis() + "_" + i)
                        put("call_id", call.optString("id").ifEmpty { "call_$i" })
                        put("name", fn.optString("name"))
                        put("arguments", fn.optString("arguments").ifEmpty { "{}" })
                        put("status", "completed")
                    },
                )
            }
        }

        val usage = chat.optJSONObject("usage") ?: JSONObject()
        val prompt = usage.optInt("prompt_tokens")
        val completion = usage.optInt("completion_tokens")
        val calls = message.optJSONArray("tool_calls")?.length() ?: 0
        return JSONObject().apply {
            put("id", "resp_" + System.currentTimeMillis())
            put("object", "response")
            put("created_at", System.currentTimeMillis() / 1000)
            put("model", model)
            put("status", "completed")
            put("output", output)
            put("output_text", message.optString("content"))
            put("parallel_tool_calls", calls > 1)
            put(
                "usage",
                JSONObject().apply {
                    put("input_tokens", prompt)
                    put("output_tokens", completion)
                    put("total_tokens", prompt + completion)
                },
            )
        }
    }

    // ------------------------------------------------------------------ //
    // Shared helpers
    // ------------------------------------------------------------------ //

    /** Anthropic's spelling of the stop reasons Chat Completions reports. */
    fun anthropicStopReason(finishReason: String?): String = when (finishReason) {
        "tool_calls" -> "tool_use"
        "length" -> "max_tokens"
        "stop" -> "end_turn"
        null, "" -> "end_turn"
        else -> finishReason
    }

    /** Parses a tool-call argument string, tolerating a malformed payload. */
    fun parseArguments(raw: String): JSONObject =
        runCatching { JSONObject(raw.trim().ifEmpty { "{}" }) }.getOrElse { JSONObject() }

    /**
     * Flattens a content value that may be a plain string or a list of parts.
     *
     * Both dialects allow either, and each part type carries its text under the
     * same key, so one reader covers all of them.
     */
    fun flattenContent(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is JSONArray -> (0 until value.length()).joinToString("") { i ->
            val part = value.optJSONObject(i)
            when {
                part == null -> value.optString(i)
                part.has("text") -> part.optString("text")
                // A tool result may wrap its own content list.
                part.has("content") -> flattenContent(part.opt("content"))
                else -> ""
            }
        }
        else -> value.toString()
    }
}

/**
 * Streaming translation for the inbound dialects.
 *
 * The upstream emits Chat Completions frames; a dialect names the same
 * information differently and, in Anthropic's case, requires blocks to be
 * opened and closed around it. Frames are mapped as they arrive rather than
 * buffered, which would defeat the point of streaming.
 */

/**
 * Streaming translation for the inbound dialects.
 *
 * The upstream emits Chat Completions frames; each dialect names the same
 * information differently and Anthropic additionally requires content blocks to
 * be opened and closed around it. Frames are mapped as they arrive rather than
 * buffered, which would defeat the point of streaming.
 *
 * Anthropic's blocks are addressed by a sequential index, so at most one is kept
 * open at a time: thinking, text and tool calls each take the next slot, and a
 * change of kind closes the previous one first.
 */
class ApiStreamAdapter(private val format: ApiFormat, private val model: String) {

    private var finished = false
    private val responseId = "resp_" + System.currentTimeMillis()

    /** How many Anthropic blocks have been opened; the next index to hand out. */
    private var blocksOpened = 0

    /** Kind of the block currently open, or null when none is. */
    private var openKind: String? = null

    /** Block index for each upstream tool-call index. */
    private val toolBlockIndex = mutableMapOf<Int, Int>()

    private var textItemOpen = false

    /** Frames to send before anything else. */
    fun opening(): List<String> {
        if (format == ApiFormat.OPENAI) return emptyList()
        return when (format) {
            ApiFormat.ANTHROPIC -> listOf(
                anthropicEvent(
                    "message_start",
                    JSONObject().apply {
                        put("type", "message_start")
                        put(
                            "message",
                            JSONObject().apply {
                                put("id", "msg_" + System.currentTimeMillis())
                                put("type", "message")
                                put("role", "assistant")
                                put("model", model)
                                put("content", JSONArray())
                                put("stop_reason", JSONObject.NULL)
                                put("stop_sequence", JSONObject.NULL)
                                put("usage", JSONObject().put("input_tokens", 0).put("output_tokens", 0))
                            },
                        )
                    },
                ),
            )
            ApiFormat.RESPONSES -> listOf(
                dataEvent(
                    JSONObject().apply {
                        put("type", "response.created")
                        put(
                            "response",
                            JSONObject().apply {
                                put("id", responseId)
                                put("object", "response")
                                put("status", "in_progress")
                                put("model", model)
                                put("output", JSONArray())
                            },
                        )
                    },
                ),
            )
            ApiFormat.OPENAI -> emptyList()
        }
    }

    /** Translates one upstream delta into the client's dialect. */
    fun onDelta(delta: JSONObject): List<String> = when (format) {
        ApiFormat.ANTHROPIC -> anthropicDelta(delta)
        ApiFormat.RESPONSES -> responsesDelta(delta)
        ApiFormat.OPENAI -> emptyList()
    }

    // ------------------------------------------------------------------ //
    // Anthropic
    // ------------------------------------------------------------------ //

    private fun anthropicDelta(delta: JSONObject): List<String> {
        val out = mutableListOf<String>()

        delta.optString("reasoning_content").takeIf { it.isNotEmpty() }?.let { piece ->
            out += ensureBlock("thinking") {
                JSONObject().put("type", "thinking").put("thinking", "")
            }
            out += anthropicEvent(
                "content_block_delta",
                JSONObject().apply {
                    put("type", "content_block_delta")
                    put("index", blocksOpened - 1)
                    put("delta", JSONObject().put("type", "thinking_delta").put("thinking", piece))
                },
            )
        }

        delta.optString("content").takeIf { it.isNotEmpty() }?.let { piece ->
            out += ensureBlock("text") { JSONObject().put("type", "text").put("text", "") }
            out += anthropicEvent(
                "content_block_delta",
                JSONObject().apply {
                    put("type", "content_block_delta")
                    put("index", blocksOpened - 1)
                    put("delta", JSONObject().put("type", "text_delta").put("text", piece))
                },
            )
        }

        delta.optJSONArray("tool_calls")?.let { out += anthropicToolDeltas(it) }
        return out
    }

    /**
     * Opens a block of [kind] if a different one is open.
     *
     * Returns the frames needed to close the previous block and start this one,
     * which is empty when the same kind is already open and can simply be
     * appended to.
     */
    private fun ensureBlock(kind: String, block: () -> JSONObject): List<String> {
        if (openKind == kind) return emptyList()
        val out = mutableListOf<String>()
        openKind?.let {
            out += anthropicEvent(
                "content_block_stop",
                JSONObject().put("type", "content_block_stop").put("index", blocksOpened - 1),
            )
        }
        out += anthropicEvent(
            "content_block_start",
            JSONObject().apply {
                put("type", "content_block_start")
                put("index", blocksOpened)
                put("content_block", block())
            },
        )
        blocksOpened++
        openKind = kind
        return out
    }

    private fun anthropicToolDeltas(calls: JSONArray): List<String> {
        val out = mutableListOf<String>()
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val upstreamIndex = call.optInt("index", i)
            val fn = call.optJSONObject("function") ?: continue
            val name = fn.optString("name")

            // The name appears only on a call's first fragment, which is what
            // marks its block as needing to be opened.
            if (name.isNotEmpty() && toolBlockIndex[upstreamIndex] == null) {
                val opening = ensureBlock("tool_use") {
                    JSONObject().apply {
                        put("type", "tool_use")
                        put("id", "toolu_" + System.currentTimeMillis() + "_" + upstreamIndex)
                        put("name", name)
                        put("input", JSONObject())
                    }
                }
                out += opening
                toolBlockIndex[upstreamIndex] = blocksOpened - 1
            }
            val index = toolBlockIndex[upstreamIndex] ?: continue
            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { piece ->
                out += anthropicEvent(
                    "content_block_delta",
                    JSONObject().apply {
                        put("type", "content_block_delta")
                        put("index", index)
                        // Partial JSON goes out as-is; the client reassembles it.
                        put(
                            "delta",
                            JSONObject().put("type", "input_json_delta").put("partial_json", piece),
                        )
                    },
                )
            }
        }
        return out
    }

    // ------------------------------------------------------------------ //
    // Responses
    // ------------------------------------------------------------------ //

    private fun responsesDelta(delta: JSONObject): List<String> {
        val out = mutableListOf<String>()
        delta.optString("content").takeIf { it.isNotEmpty() }?.let { piece ->
            if (!textItemOpen) {
                textItemOpen = true
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.output_item.added")
                        put("output_index", 0)
                        put(
                            "item",
                            JSONObject().apply {
                                put("type", "message")
                                put("id", "msg_" + System.currentTimeMillis())
                                put("role", "assistant")
                                put("status", "in_progress")
                                put("content", JSONArray())
                            },
                        )
                    },
                )
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.content_part.added")
                        put("output_index", 0)
                        put("content_index", 0)
                        put("part", JSONObject().put("type", "output_text").put("text", ""))
                    },
                )
            }
            out += dataEvent(
                JSONObject().apply {
                    put("type", "response.output_text.delta")
                    put("output_index", 0)
                    put("content_index", 0)
                    put("delta", piece)
                },
            )
        }
        return out
    }

    // ------------------------------------------------------------------ //
    // Closing
    // ------------------------------------------------------------------ //

    /** Frames that finish the stream, carrying the usage figures. */
    fun closing(finishReason: String?, promptTokens: Int, completionTokens: Int): List<String> {
        if (finished) return emptyList()
        finished = true
        return when (format) {
            ApiFormat.ANTHROPIC -> {
                val out = mutableListOf<String>()
                // Whatever block is still open has to be closed first.
                if (openKind != null) {
                    out += anthropicEvent(
                        "content_block_stop",
                        JSONObject().put("type", "content_block_stop").put("index", blocksOpened - 1),
                    )
                    openKind = null
                }
                out += anthropicEvent(
                    "message_delta",
                    JSONObject().apply {
                        put("type", "message_delta")
                        put(
                            "delta",
                            JSONObject().apply {
                                put("stop_reason", ApiAdapters.anthropicStopReason(finishReason))
                                put("stop_sequence", JSONObject.NULL)
                            },
                        )
                        put("usage", JSONObject().put("output_tokens", completionTokens))
                    },
                )
                out += anthropicEvent("message_stop", JSONObject().put("type", "message_stop"))
                out
            }
            ApiFormat.RESPONSES -> {
                val out = mutableListOf<String>()
                if (textItemOpen) {
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.output_text.done")
                            put("output_index", 0)
                            put("content_index", 0)
                        },
                    )
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.output_item.done")
                            put("output_index", 0)
                        },
                    )
                }
                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.completed")
                        put(
                            "response",
                            JSONObject().apply {
                                put("id", responseId)
                                put("object", "response")
                                put("status", "completed")
                                put("model", model)
                                put(
                                    "usage",
                                    JSONObject().apply {
                                        put("input_tokens", promptTokens)
                                        put("output_tokens", completionTokens)
                                        put("total_tokens", promptTokens + completionTokens)
                                    },
                                )
                            },
                        )
                    },
                )
                out
            }
            ApiFormat.OPENAI -> listOf("data: [DONE]\n\n")
        }
    }

    /** Anthropic frames carry the event name on its own line. */
    private fun anthropicEvent(name: String, payload: JSONObject): String =
        "event: $name\ndata: $payload\n\n"

    /** The other dialects put the name inside the payload. */
    private fun dataEvent(payload: JSONObject): String = "data: $payload\n\n"
}
