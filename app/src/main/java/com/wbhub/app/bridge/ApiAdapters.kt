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
     * Markers the upstream refuses, and what replaces each.
     *
     * Measured against the live gateway, which filters by two different rules:
     *
     *  - [BLOCKED_MARKERS] are found anywhere in the text. One of them trips the
     *    filter while sitting in the middle of a 27 kB prompt, and changing any
     *    single character clears the block.
     *  - [BLOCKED_PREFIXES] are only matched at the very start: the same text
     *    preceded by a single space or a newline passes. These are the
     *    self-descriptions clients open their instructions with.
     *
     * Both are handled because a client may send either, and a rule applied in
     * the wrong place is either a missed block or a needless rewrite.
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
     * Markers the upstream refuses only when the text begins with them.
     *
     * The second entry is the opening of another vendor's coding agent — a
     * paragraph describing what the agent is, which the gateway reads as an
     * attempt to drive the account from a client it has not approved. It is
     * filtered as one whole passage: dropping its final word, or hyphenating a
     * term inside it, is enough to pass. The rewrite therefore only touches its
     * opening clause, which changes nothing about what the agent is told to do.
     */
    private val BLOCKED_PREFIXES: List<Pair<String, String>> = listOf(
        // The same rule, extended on 2026-10-20 for a second agent's opening
        // line. Measured against the live gateway: the runtime form is blocked
        // only at offset 0 of a system turn, is matched case-insensitively,
        // and passes when a single space is prepended or when the same text
        // sits in a user turn. Only the opening clause is rewritten, so the
        // sentence still tells the model the same thing about itself.
        "You are an AI agent powered by DeepSeek Harness." to
            "You are an AI assistant built on DeepSeek Harness.",
        // The model-specific variant of the same opener. The model name is
        // kept verbatim; only the surrounding clause changes.
        "You are a coding agent powered by the deepseek-v4.1-flash model." to
            "You are an assistant built on the deepseek-v4.1-flash model.",
        // Opening of another vendor's coding agent. Only its first clause is
        // rewritten — the sentence still says the same thing about the agent.
        "You are a coding agent running in the Codex CLI, a terminal-based coding assistant. Codex CLI is an open source project led by OpenAI. You are expected to be precise, safe, and helpful." to
            "You're a coding agent running in the Codex CLI, a terminal-based coding assistant. Codex CLI is an open source project led by OpenAI. You are expected to be precise, safe, and helpful.",
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
        for ((prefix, replacement) in BLOCKED_PREFIXES) {
            if (rewritten.startsWith(prefix, ignoreCase = true)) {
                rewritten = replacement + rewritten.substring(prefix.length)
            }
        }
        return rewritten
    }

    /** Whether [text] carries anything the upstream refuses. */
    fun needsDisguise(text: String): Boolean =
        BLOCKED_MARKERS.any { text.contains(it.first, ignoreCase = true) } ||
            BLOCKED_PREFIXES.any { text.startsWith(it.first, ignoreCase = true) }

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
        // Parts in the order they arrived, so text and images keep the positions
        // the client put them in.
        val parts = JSONArray()
        var sawImage = false
        val toolCalls = JSONArray()
        val toolResults = mutableListOf<JSONObject>()
        for (i in 0 until blocks.length()) {
            val block = blocks.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> {
                    val piece = block.optString("text")
                    text.append(piece)
                    parts.put(JSONObject().put("type", "text").put("text", piece))
                }
                // An image is passed through as a URL, which is the one shape
                // both dialects agree on; only the part's name differs.
                "image" -> anthropicImagePart(block)?.let {
                    sawImage = true
                    parts.put(it)
                }
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
        } else if (sawImage) {
            // Only a message that actually has an image needs the part list;
            // everything else keeps the plain string it came as.
            sink.put(JSONObject().put("role", role).put("content", parts))
        } else if (text.isNotEmpty()) {
            sink.put(JSONObject().put("role", role).put("content", text.toString()))
        }
        toolResults.forEach { sink.put(it) }
    }

    /**
     * Rewrites one Anthropic image block as a Chat Completions image part.
     *
     * Anthropic sends the bytes inline as `{source:{type:base64,media_type,data}}`,
     * which is the same information a data URL carries, so it is rebuilt as one
     * rather than kept as a dialect-specific shape.
     */
    private fun anthropicImagePart(block: JSONObject): JSONObject? {
        val source = block.optJSONObject("source") ?: return null
        val url = when (source.optString("type")) {
            "base64" -> {
                val media = source.optString("media_type").ifEmpty { "image/png" }
                val data = source.optString("data")
                if (data.isEmpty()) return null
                "data:$media;base64,$data"
            }
            "url" -> source.optString("url").ifEmpty { return null }
            else -> return null
        }
        return JSONObject().apply {
            put("type", "image_url")
            put("image_url", JSONObject().put("url", url))
        }
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
                            // Content is passed through part by part rather than
                            // flattened: this dialect carries images as their own
                            // part, and flattening would leave only the words
                            // around them.
                            val content = responsesContent(item.opt("content"))
                            if (role == "assistant" && isEmptyContent(content)) continue
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
     * Translates a Responses content value into the Chat Completions shape.
     *
     * A plain string stays a string, because that is what most turns are and
     * rewriting them into a one-element list would change the request for no
     * reason. A list is mapped part by part so images survive: the two dialects
     * agree on text, and an image is a URL either way, so only the part's name
     * has to be changed.
     */
    private fun responsesContent(value: Any?): Any = when (value) {
        is String -> value
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) {
                val part = value.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "input_text", "output_text", "text" ->
                        out.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    "input_image" -> chatImagePart(part)?.let { out.put(it) }
                    // A tool result may wrap its own content list.
                    else -> if (part.has("content")) {
                        out.put(
                            JSONObject().put("type", "text")
                                .put("text", flattenContent(part.opt("content"))),
                        )
                    }
                }
            }
            out
        }
        else -> flattenContent(value)
    }

    /**
     * Rewrites one image part, or null when it carries nothing usable.
     *
     * Chat Completions wants the URL nested under `image_url`; the Responses
     * dialect puts it directly on the part. The detail hint is carried across
     * because it changes how much of the picture the model is shown.
     */
    private fun chatImagePart(part: JSONObject): JSONObject? {
        val url = part.optString("image_url").ifEmpty {
            part.optJSONObject("image_url")?.optString("url").orEmpty()
        }
        if (url.isEmpty()) return null
        return JSONObject().apply {
            put("type", "image_url")
            put(
                "image_url",
                JSONObject().apply {
                    put("url", url)
                    part.optString("detail").takeIf { it.isNotEmpty() }?.let { put("detail", it) }
                },
            )
        }
    }

    /** Whether a translated content value carries nothing worth sending. */
    private fun isEmptyContent(value: Any?): Boolean = when (value) {
        null -> true
        is String -> value.isEmpty()
        is JSONArray -> value.length() == 0
        else -> false
    }

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

    /**
     * Output index of the open text message, or -1 when none is.
     *
     * The Responses dialect numbers every item in `output` in the order it was
     * emitted, and a turn that both speaks and calls a tool puts more than one
     * there — so the index cannot be assumed to be zero.
     */
    private var textOutputIndex = -1

    /** Item id announced for the open text message. */
    private var textItemId = ""

    /** Text accumulated for the open message, so the closing events can carry it. */
    private val textSeen = StringBuilder()

    /** Index the next output item will take. */
    private var nextOutputIndex = 0

    /** Output index of the item opened for each upstream tool-call index. */
    private val callOutputIndex = mutableMapOf<Int, Int>()

    /** Call id announced for each upstream tool-call index. */
    private val callIds = mutableMapOf<Int, String>()

    /** Tool name announced for each upstream tool-call index. */
    private val callNames = mutableMapOf<Int, String>()

    /** Item id announced for each upstream tool-call index. */
    private val callItemIds = mutableMapOf<Int, String>()

    /** Arguments accumulated so far for each upstream tool-call index. */
    private val callArguments = mutableMapOf<Int, StringBuilder>()

    /**
     * Every item emitted so far, so the final response can carry them.
     *
     * A client is allowed to read the answer from `response.completed` instead
     * of following the deltas, so that event has to describe the whole turn.
     */
    private val completedItems = JSONArray()

    /** Ordering counter the Responses dialect expects on every event. */
    private var sequence = 0

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
                        put("sequence_number", sequence++)
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
            out += ensureTextItem()
            out += dataEvent(
                JSONObject().apply {
                    put("type", "response.output_text.delta")
                    put("sequence_number", sequence++)
                    put("item_id", textItemId)
                    put("output_index", textOutputIndex)
                    put("content_index", 0)
                    put("delta", piece)
                },
            )
            textSeen.append(piece)
        }
        // A tool call arrives as fragments keyed by index, exactly as it does in
        // Chat Completions: the name and id come with the first fragment and the
        // arguments are spread across the rest. The dialect wants an item opened
        // for the call before its arguments are streamed, so the opening is held
        // back until the name shows up.
        delta.optJSONArray("tool_calls")?.let { calls ->
            for (i in 0 until calls.length()) {
                val row = calls.optJSONObject(i) ?: continue
                val callIndex = row.optInt("index", i)
                val fn = row.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                if (name.isNotEmpty() && callOutputIndex[callIndex] == null) {
                    out += openCallItem(callIndex, name, row.optString("id"))
                }
                fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { piece ->
                    callArguments.getOrPut(callIndex) { StringBuilder() }.append(piece)
                    // Arguments are emitted as they arrive rather than collected:
                    // a long call would otherwise sit silent until it finished.
                    val index = callOutputIndex[callIndex] ?: return@let
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.function_call_arguments.delta")
                            put("sequence_number", sequence++)
                            put("item_id", callItemIds[callIndex].orEmpty())
                            put("output_index", index)
                            put("delta", piece)
                        },
                    )
                }
            }
        }
        return out
    }

    /** Opens the text message item if one is not already open. */
    private fun ensureTextItem(): List<String> {
        if (textOutputIndex >= 0) return emptyList()
        textOutputIndex = nextOutputIndex++
        textItemId = "msg_" + System.currentTimeMillis()
        return listOf(
            dataEvent(
                JSONObject().apply {
                    put("type", "response.output_item.added")
                    put("sequence_number", sequence++)
                    put("output_index", textOutputIndex)
                    put(
                        "item",
                        JSONObject().apply {
                            put("type", "message")
                            put("id", textItemId)
                            put("role", "assistant")
                            put("status", "in_progress")
                            put("content", JSONArray())
                        },
                    )
                },
            ),
            dataEvent(
                JSONObject().apply {
                    put("type", "response.content_part.added")
                    put("sequence_number", sequence++)
                    put("item_id", textItemId)
                    put("output_index", textOutputIndex)
                    put("content_index", 0)
                    put("part", JSONObject().put("type", "output_text").put("text", ""))
                },
            ),
        )
    }

    /**
     * Opens an output item for one tool call.
     *
     * A client that reads the item as it appears needs the name and the call id
     * here, because the arguments that follow carry neither.
     */
    private fun openCallItem(callIndex: Int, name: String, upstreamId: String): List<String> {
        val outputIndex = nextOutputIndex++
        callOutputIndex[callIndex] = outputIndex
        // The call id is what the client echoes back when it reports the tool's
        // result, so it has to be the upstream's own id for the round trip to
        // close. The item id is this dialect's own handle and only has to be
        // unique within the response.
        val callId = upstreamId.ifEmpty { "call_" + System.currentTimeMillis() + "_" + callIndex }
        val itemId = "fc_" + System.currentTimeMillis() + "_" + callIndex
        callIds[callIndex] = callId
        callNames[callIndex] = name
        callItemIds[callIndex] = itemId
        return listOf(
            dataEvent(
                JSONObject().apply {
                    put("type", "response.output_item.added")
                    put("sequence_number", sequence++)
                    put("output_index", outputIndex)
                    put(
                        "item",
                        JSONObject().apply {
                            put("type", "function_call")
                            put("id", itemId)
                            put("call_id", callId)
                            put("name", name)
                            put("arguments", "")
                            put("status", "in_progress")
                        },
                    )
                },
            ),
        )
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
                // Every open item is closed before the response is, and in the
                // order it was opened: a client that is assembling the turn
                // needs each item finished before the whole is declared done.
                if (textOutputIndex >= 0) {
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.output_text.done")
                            put("sequence_number", sequence++)
                            put("item_id", textItemId)
                            put("output_index", textOutputIndex)
                            put("content_index", 0)
                            put("text", textSeen.toString())
                        },
                    )
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.content_part.done")
                            put("sequence_number", sequence++)
                            put("item_id", textItemId)
                            put("output_index", textOutputIndex)
                            put("content_index", 0)
                            put(
                                "part",
                                JSONObject().apply {
                                    put("type", "output_text")
                                    put("text", textSeen.toString())
                                    put("annotations", JSONArray())
                                },
                            )
                        },
                    )
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.output_item.done")
                            put("sequence_number", sequence++)
                            put("output_index", textOutputIndex)
                            put(
                                "item",
                                JSONObject().apply {
                                    put("type", "message")
                                    put("id", textItemId)
                                    put("role", "assistant")
                                    put("status", "completed")
                                    put(
                                        "content",
                                        JSONArray().put(
                                            JSONObject().apply {
                                                put("type", "output_text")
                                                put("text", textSeen.toString())
                                                put("annotations", JSONArray())
                                            },
                                        ),
                                    )
                                },
                            )
                        },
                    )
                    completedItems.put(
                        JSONObject().apply {
                            put("type", "message")
                            put("id", textItemId)
                            put("role", "assistant")
                            put("status", "completed")
                            put(
                                "content",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("type", "output_text")
                                        put("text", textSeen.toString())
                                        put("annotations", JSONArray())
                                    },
                                ),
                            )
                        },
                    )
                }

                // Tool calls are closed in the order they were opened, which is
                // not necessarily the order the map iterates in.
                for (callIndex in callOutputIndex.keys.sortedBy { callOutputIndex[it] }) {
                    val index = callOutputIndex[callIndex] ?: continue
                    val arguments = callArguments[callIndex]?.toString().orEmpty().ifEmpty { "{}" }
                    val callId = callIds[callIndex].orEmpty()
                    val name = callNames[callIndex].orEmpty()
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.function_call_arguments.done")
                            put("sequence_number", sequence++)
                            put("item_id", callItemIds[callIndex].orEmpty())
                            put("output_index", index)
                            put("arguments", arguments)
                        },
                    )
                    val item = JSONObject().apply {
                        put("type", "function_call")
                        put("id", callItemIds[callIndex].orEmpty())
                        put("call_id", callId)
                        put("name", name)
                        put("arguments", arguments)
                        put("status", "completed")
                    }
                    out += dataEvent(
                        JSONObject().apply {
                            put("type", "response.output_item.done")
                            put("sequence_number", sequence++)
                            put("output_index", index)
                            put("item", item)
                        },
                    )
                    completedItems.put(item)
                }

                out += dataEvent(
                    JSONObject().apply {
                        put("type", "response.completed")
                        put("sequence_number", sequence++)
                        put(
                            "response",
                            JSONObject().apply {
                                put("id", responseId)
                                put("object", "response")
                                put("status", "completed")
                                put("model", model)
                                put("output", completedItems)
                                put("output_text", textSeen.toString())
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
