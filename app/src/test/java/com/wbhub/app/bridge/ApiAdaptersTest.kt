package com.wbhub.app.bridge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between the dialects, pinned to what LiteLLM does.
 *
 * Every case here corresponds to a rule in LiteLLM's
 * `pass_through/adapters/transformation.py` (Anthropic) or
 * `pass_through/responses_adapters/transformation.py` (Responses). The point is
 * not that these are the only correct answers, but that a client written
 * against the real APIs must not be able to tell which shape it was translated
 * from.
 */
class ApiAdaptersTest {

    // ------------------------------------------------------------------ //
    // Anthropic inbound
    // ------------------------------------------------------------------ //

    @Test
    fun `system prompt moves into the message list`() {
        val body = JSONObject()
            .put("model", "m")
            .put("system", "be brief")
            .put("messages", JSONArray().put(user("hi")))
            .put("max_tokens", 8)

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)

        val messages = chat.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be brief", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `a system prompt given as blocks is joined`() {
        val system = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "one"))
            .put(JSONObject().put("type", "text").put("text", "two"))
        val body = JSONObject()
            .put("model", "m")
            .put("system", system)
            .put("messages", JSONArray().put(user("hi")))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)

        // LiteLLM joins the text of every block with a newline.
        assertEquals("one\ntwo", chat.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test
    fun `tool use becomes a tool call with parsed arguments`() {
        val assistant = JSONObject()
            .put("role", "assistant")
            .put(
                "content",
                JSONArray().put(
                    JSONObject()
                        .put("type", "tool_use")
                        .put("id", "toolu_1")
                        .put("name", "read_file")
                        .put("input", JSONObject().put("path", "/etc/hosts")),
                ),
            )
        val body = JSONObject()
            .put("model", "m")
            .put("messages", JSONArray().put(user("read it")).put(assistant))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val message = chat.getJSONArray("messages").getJSONObject(1)
        val call = message.getJSONArray("tool_calls").getJSONObject(0)

        assertEquals("assistant", message.getString("role"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))
        // The arguments travel as a string, so the object has to be serialised.
        val args = JSONObject(call.getJSONObject("function").getString("arguments"))
        assertEquals("/etc/hosts", args.getString("path"))
    }

    @Test
    fun `tool result becomes a tool message`() {
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(user("read it")).put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_1")
                                .put("content", "127.0.0.1 localhost"),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val tool = chat.getJSONArray("messages").getJSONObject(1)

        assertEquals("tool", tool.getString("role"))
        assertEquals("toolu_1", tool.getString("tool_call_id"))
        assertEquals("127.0.0.1 localhost", tool.getString("content"))
    }

    @Test
    fun `a tool result carrying several blocks keeps them all`() {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", "line one"))
            .put(JSONObject().put("type", "text").put("text", "line two"))
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_1")
                                .put("content", content),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val tool = chat.getJSONArray("messages").getJSONObject(0)

        // LiteLLM returns a single text part as a plain string and a list as a
        // part list; either way no line may be dropped.
        val flattened = ApiAdapters.flattenContent(tool.get("content"))
        assertTrue("lost the first line: $flattened", flattened.contains("line one"))
        assertTrue("lost the second line: $flattened", flattened.contains("line two"))
    }

    @Test
    fun `an errored tool result is marked as an error`() {
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", "toolu_1")
                                .put("is_error", true)
                                .put("content", "no such file"),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val tool = chat.getJSONArray("messages").getJSONObject(0)
        val text = ApiAdapters.flattenContent(tool.get("content"))

        // The model has to be able to tell a failure from an answer, or it
        // retries the same call forever.
        assertTrue("error flag dropped: $text", text.contains("no such file"))
        assertTrue("no sign of the failure: $text", text.contains("error", ignoreCase = true))
    }

    @Test
    fun `a base64 image becomes a data url`() {
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "image/png")
                                        .put("data", "AAAB"),
                                ),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val part = chat.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)

        assertEquals("image_url", part.getString("type"))
        assertEquals("data:image/png;base64,AAAB", part.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `a url image keeps its url`() {
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "url")
                                        .put("url", "https://example.test/a.png"),
                                ),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val part = chat.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)

        assertEquals("https://example.test/a.png", part.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `tool schemas are nested under function`() {
        val tool = JSONObject()
            .put("name", "read_file")
            .put("description", "read a file")
            .put("input_schema", JSONObject().put("type", "object"))
        val body = JSONObject()
            .put("model", "m")
            .put("messages", JSONArray().put(user("hi")))
            .put("tools", JSONArray().put(tool))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val translated = chat.getJSONArray("tools").getJSONObject(0)

        assertEquals("function", translated.getString("type"))
        assertEquals("read_file", translated.getJSONObject("function").getString("name"))
        assertNotNull(translated.getJSONObject("function").optJSONObject("parameters"))
    }

    @Test
    fun `stop sequences are carried across`() {
        val body = JSONObject()
            .put("model", "m")
            .put("messages", JSONArray().put(user("hi")))
            .put("stop_sequences", JSONArray().put("\n\nHuman:"))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)

        assertEquals("\n\nHuman:", chat.getJSONArray("stop").getString(0))
    }

    @Test
    fun `tool choice is carried across`() {
        val body = JSONObject()
            .put("model", "m")
            .put("messages", JSONArray().put(user("hi")))
            .put("tool_choice", JSONObject().put("type", "any"))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)

        // Anthropic's "any" means the model must call some tool.
        assertEquals("required", chat.getJSONObject("tool_choice").getString("type"))
    }

    @Test
    fun `a document block is not silently dropped`() {
        val body = JSONObject()
            .put("model", "m")
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "document")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "application/pdf")
                                        .put("data", "JVBERi0="),
                                ),
                        ),
                    ),
                ),
            )

        val chat = ApiAdapters.toChatBody(body, ApiFormat.ANTHROPIC)
        val messages = chat.getJSONArray("messages")

        // The bytes have to reach the model one way or another; losing the
        // whole turn is the worst possible outcome.
        assertTrue("the turn vanished entirely", messages.length() > 0)
        assertTrue(
            "the document left no trace",
            messages.toString().contains("JVBERi0="),
        )
    }

    // ------------------------------------------------------------------ //
    // Responses inbound
    // ------------------------------------------------------------------ //

    @Test
    fun `instructions become a system message`() {
        val body = JSONObject()
            .put("model", "m")
            .put("instructions", "be brief")
            .put("input", "hi")

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)
        val messages = chat.getJSONArray("messages")

        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("be brief", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun `a function call is replayed on the assistant turn`() {
        val input = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "read it"))
            .put(
                JSONObject()
                    .put("type", "function_call")
                    .put("call_id", "call_1")
                    .put("name", "read_file")
                    .put("arguments", """{"path":"/x"}"""),
            )
            .put(
                JSONObject()
                    .put("type", "function_call_output")
                    .put("call_id", "call_1")
                    .put("output", "contents"),
            )
        val body = JSONObject().put("model", "m").put("input", input)

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)
        val messages = chat.getJSONArray("messages")

        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("call_1", call.getString("id"))
        assertEquals("read_file", call.getJSONObject("function").getString("name"))

        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        assertEquals("contents", tool.getString("content"))
    }

    @Test
    fun `several function calls share one assistant turn`() {
        val input = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "go"))
            .put(JSONObject().put("type", "function_call").put("call_id", "a").put("name", "one").put("arguments", "{}"))
            .put(JSONObject().put("type", "function_call").put("call_id", "b").put("name", "two").put("arguments", "{}"))
        val body = JSONObject().put("model", "m").put("input", input)

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)
        val messages = chat.getJSONArray("messages")

        assertEquals(2, messages.length())
        val assistant = messages.getJSONObject(1)
        assertEquals(2, assistant.getJSONArray("tool_calls").length())
    }

    @Test
    fun `an input image keeps its url and detail`() {
        val part = JSONObject()
            .put("type", "input_image")
            .put("image_url", "data:image/png;base64,AAAB")
            .put("detail", "high")
        val input = JSONArray().put(
            JSONObject().put("role", "user").put("content", JSONArray().put(part)),
        )
        val body = JSONObject().put("model", "m").put("input", input)

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)
        val translated = chat.getJSONArray("messages").getJSONObject(0)
            .getJSONArray("content").getJSONObject(0)

        assertEquals("image_url", translated.getString("type"))
        assertEquals("data:image/png;base64,AAAB", translated.getJSONObject("image_url").getString("url"))
        assertEquals("high", translated.getJSONObject("image_url").getString("detail"))
    }

    @Test
    fun `max output tokens is renamed`() {
        val body = JSONObject()
            .put("model", "m")
            .put("input", "hi")
            .put("max_output_tokens", 64)

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)

        assertEquals(64, chat.getInt("max_tokens"))
        assertFalse(chat.has("max_output_tokens"))
    }

    @Test
    fun `flat responses tools are nested`() {
        val tool = JSONObject()
            .put("type", "function")
            .put("name", "read_file")
            .put("description", "read a file")
            .put("parameters", JSONObject().put("type", "object"))
        val body = JSONObject()
            .put("model", "m")
            .put("input", "hi")
            .put("tools", JSONArray().put(tool))

        val chat = ApiAdapters.toChatBody(body, ApiFormat.RESPONSES)
        val translated = chat.getJSONArray("tools").getJSONObject(0)

        assertEquals("read_file", translated.getJSONObject("function").getString("name"))
    }

    // ------------------------------------------------------------------ //
    // Outbound
    // ------------------------------------------------------------------ //

    @Test
    fun `an answer becomes an anthropic message`() {
        val chat = JSONObject()
            .put("model", "m")
            .put("choices", JSONArray().put(choice(stop = "stop", text = "hello")))
            .put("usage", JSONObject().put("prompt_tokens", 3).put("completion_tokens", 2))

        val anthropic = ApiAdapters.fromChatResponse(chat, ApiFormat.ANTHROPIC, "m")

        assertEquals("message", anthropic.getString("type"))
        assertEquals("end_turn", anthropic.getString("stop_reason"))
        val block = anthropic.getJSONArray("content").getJSONObject(0)
        assertEquals("text", block.getString("type"))
        assertEquals("hello", block.getString("text"))
        assertEquals(3, anthropic.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(2, anthropic.getJSONObject("usage").getInt("output_tokens"))
    }

    @Test
    fun `a tool call becomes a tool use block`() {
        val message = JSONObject().put("content", "")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", "read_file").put("arguments", """{"path":"/x"}"""),
                        ),
                ),
            )
        val chat = JSONObject()
            .put("model", "m")
            .put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", "tool_calls")))

        val anthropic = ApiAdapters.fromChatResponse(chat, ApiFormat.ANTHROPIC, "m")

        assertEquals("tool_use", anthropic.getString("stop_reason"))
        val block = anthropic.getJSONArray("content").getJSONObject(0)
        assertEquals("tool_use", block.getString("type"))
        assertEquals("call_1", block.getString("id"))
        // Arguments have to come back as an object, which is what the field means.
        assertEquals("/x", block.getJSONObject("input").getString("path"))
    }

    @Test
    fun `reasoning content becomes a thinking block`() {
        val message = JSONObject()
            .put("content", "answer")
            .put("reasoning_content", "because")
        val chat = JSONObject()
            .put("model", "m")
            .put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", "stop")))

        val anthropic = ApiAdapters.fromChatResponse(chat, ApiFormat.ANTHROPIC, "m")
        val content = anthropic.getJSONArray("content")

        assertEquals("thinking", content.getJSONObject(0).getString("type"))
        assertEquals("because", content.getJSONObject(0).getString("thinking"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
    }

    @Test
    fun `an answer becomes a responses object`() {
        val chat = JSONObject()
            .put("model", "m")
            .put("choices", JSONArray().put(choice(stop = "stop", text = "hello")))
            .put("usage", JSONObject().put("prompt_tokens", 3).put("completion_tokens", 2))

        val responses = ApiAdapters.fromChatResponse(chat, ApiFormat.RESPONSES, "m")

        assertEquals("response", responses.getString("object"))
        assertEquals("completed", responses.getString("status"))
        assertEquals("hello", responses.getString("output_text"))
        val item = responses.getJSONArray("output").getJSONObject(0)
        assertEquals("message", item.getString("type"))
        assertEquals("output_text", item.getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals(3, responses.getJSONObject("usage").getInt("input_tokens"))
    }

    @Test
    fun `a tool call becomes a function call item`() {
        val message = JSONObject().put("content", "")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_1")
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", "read_file").put("arguments", """{"path":"/x"}"""),
                        ),
                ),
            )
        val chat = JSONObject()
            .put("model", "m")
            .put("choices", JSONArray().put(JSONObject().put("message", message).put("finish_reason", "tool_calls")))

        val responses = ApiAdapters.fromChatResponse(chat, ApiFormat.RESPONSES, "m")
        val item = responses.getJSONArray("output").getJSONObject(0)

        assertEquals("function_call", item.getString("type"))
        assertEquals("call_1", item.getString("call_id"))
        assertEquals("read_file", item.getString("name"))
    }

    // ------------------------------------------------------------------ //
    // Streaming
    // ------------------------------------------------------------------ //

    @Test
    fun `anthropic streaming opens and closes a text block`() {
        val stream = ApiAdapters.streamer(ApiFormat.ANTHROPIC, "m")

        val opening = stream.opening()
        assertTrue(opening.any { it.contains("message_start") })

        val deltas = stream.onDelta(JSONObject().put("content", "hi"))
        assertTrue(deltas.any { it.contains("content_block_start") })
        assertTrue(deltas.any { it.contains("text_delta") })

        val closing = stream.finish("stop", 1, 2)
        assertTrue(closing.any { it.contains("content_block_stop") })
        assertTrue(closing.any { it.contains("message_delta") })
        assertTrue(closing.any { it.contains("message_stop") })
    }

    @Test
    fun `anthropic streaming never reopens a block of the same kind`() {
        val stream = ApiAdapters.streamer(ApiFormat.ANTHROPIC, "m")
        stream.opening()

        stream.onDelta(JSONObject().put("content", "a"))
        val second = stream.onDelta(JSONObject().put("content", "b"))

        // A second `content_block_start` for the same block confuses every
        // client that indexes its blocks by that event.
        assertFalse(second.any { it.contains("content_block_start") })
        assertTrue(second.any { it.contains("text_delta") })
    }

    @Test
    fun `anthropic streaming streams tool arguments as partial json`() {
        val stream = ApiAdapters.streamer(ApiFormat.ANTHROPIC, "m")
        stream.opening()

        val first = stream.onDelta(
            JSONObject().put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("id", "call_1")
                        .put("function", JSONObject().put("name", "read_file").put("arguments", "")),
                ),
            ),
        )
        assertTrue(first.any { it.contains("tool_use") })

        val second = stream.onDelta(
            JSONObject().put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("function", JSONObject().put("arguments", """{"pa""")),
                ),
            ),
        )
        assertTrue(second.any { it.contains("input_json_delta") })
        assertTrue(second.any { it.contains("partial_json") })
    }

    @Test
    fun `two tool calls get separate blocks`() {
        val stream = ApiAdapters.streamer(ApiFormat.ANTHROPIC, "m")
        stream.opening()

        val out = stream.onDelta(
            JSONObject().put(
                "tool_calls",
                JSONArray()
                    .put(
                        JSONObject().put("index", 0)
                            .put("function", JSONObject().put("name", "one").put("arguments", "")),
                    )
                    .put(
                        JSONObject().put("index", 1)
                            .put("function", JSONObject().put("name", "two").put("arguments", "")),
                    ),
            ),
        )

        // One call must not overwrite the other's block index.
        assertEquals(2, out.count { it.contains("content_block_start") })
    }

    @Test
    fun `responses streaming closes with a completed response`() {
        val stream = ApiAdapters.streamer(ApiFormat.RESPONSES, "m")
        stream.opening()

        stream.onDelta(JSONObject().put("content", "hi"))
        val closing = stream.finish("stop", 1, 2)

        assertTrue(closing.any { it.contains("response.output_text.done") })
        assertTrue(closing.any { it.contains("response.output_item.done") })
        assertTrue(closing.any { it.contains("response.completed") })
    }

    @Test
    fun `responses streaming reports a tool call`() {
        val stream = ApiAdapters.streamer(ApiFormat.RESPONSES, "m")
        stream.opening()

        stream.onDelta(
            JSONObject().put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("id", "call_1")
                        .put("function", JSONObject().put("name", "read_file").put("arguments", """{"path":"/x"}""")),
                ),
            ),
        )
        val closing = stream.finish("tool_calls", 1, 2)

        assertTrue(closing.any { it.contains("response.function_call_arguments.done") })
        val completed = closing.first { it.contains("response.completed") }
        assertTrue(completed.contains("call_1"))
        assertTrue(completed.contains("read_file"))
    }

    @Test
    fun `every responses event carries a sequence number`() {
        val stream = ApiAdapters.streamer(ApiFormat.RESPONSES, "m")
        var seen = mutableListOf<String>()
        seen += stream.opening()
        seen += stream.onDelta(JSONObject().put("content", "hi"))
        seen += stream.finish("stop", 1, 2)

        val numbers = seen.filter { it.contains("data: ") }
            .mapNotNull { frame ->
                val payload = frame.substringAfter("data: ").trim()
                if (payload.startsWith("{")) JSONObject(payload).optInt("sequence_number", -1) else null
            }
        assertTrue("no sequence numbers at all", numbers.isNotEmpty())
        assertTrue("a frame is missing one: $numbers", numbers.all { it >= 0 })
        assertEquals("numbers must increase", numbers.sorted(), numbers)
    }

    // ------------------------------------------------------------------ //
    // Helpers
    // ------------------------------------------------------------------ //

    private fun user(text: String) = JSONObject().put("role", "user").put("content", text)

    private fun choice(stop: String, text: String) = JSONObject()
        .put("message", JSONObject().put("role", "assistant").put("content", text))
        .put("finish_reason", stop)
}
