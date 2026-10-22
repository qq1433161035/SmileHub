package com.wbhub.app.proto

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzip for chat request bodies.
 *
 * A coding client re-sends its whole conversation on every turn, so its request
 * is mostly the same text repeated: the same tool schemas, the same transcript,
 * the same instructions. Measured against this gateway, a two-megabyte request
 * compresses to a few kilobytes — three orders of magnitude — while the
 * compression itself costs single-digit milliseconds. The upload is otherwise
 * several hundred milliseconds of the caller's wait, all of it in front of the
 * first token, so the trade is strongly in favour of compressing.
 *
 * Both the endpoint and the built-in chat go through [postChat], so the decision
 * to compress, and the recovery when a gateway does not accept it, are made in
 * exactly one place.
 */
object Gzip {

    private const val TAG = "WBHub"

    /**
     * One attempt and its outcome.
     *
     * The status and the failure body travel with the result because the error
     * stream can only be read once. A caller that has to decide whether to retry,
     * and then report a failure if the retry also fails, cannot re-read what it
     * already consumed — so the reading happens once, where the connection is
     * opened, and the answer is carried out.
     */
    class Attempt<T>(val value: T, val status: Int, val errorBody: String = "") {
        /** Whether the gateway accepted the request. */
        val accepted: Boolean get() = status in 200..299
    }

    /**
     * Posts a chat request, compressing the body unless [region]'s gateway has
     * been seen to refuse it, and retrying in the clear if it is refused.
     *
     * Returns the final attempt, so that a retry which also fails reports its own
     * error rather than the one that prompted the retry.
     */
    fun <T> postChat(
        region: Wire.Region,
        body: String,
        send: (bytes: ByteArray, compressed: Boolean) -> Attempt<T>,
    ): Attempt<T> {
        val plain = body.toByteArray(Charsets.UTF_8)
        if (regionRefuses(region)) return send(plain, false)

        val encoded = encode(body)
        if (!encoded.compressed) return send(plain, false)

        val first = send(encoded.bytes, true)
        if (first.accepted) return first
        // Status 0 means no readable response at all. A gateway that cannot
        // decode the body sometimes closes the connection instead of answering
        // 400, so this is treated as a possible encoding rejection once; the
        // plain retry below either succeeds or fails on its own merits.
        if (first.status == 0) {
            val retried = send(plain, false)
            // Only blame the encoding when the clear request actually worked;
            // a plain network outage should not disable compression for good.
            if (retried.accepted) {
                noteCompressionRefused(region)
                Log.w(TAG, "no response to a gzip body; retrying in the clear for $region")
            }
            return retried
        }
        if (!looksLikeRejectedEncoding(first.status, first.errorBody)) {
            return first
        }

        // The gateway cannot read a compressed body, so it is told not to send
        // one again and this request goes out in the clear. The cost of finding
        // out is paid once rather than on every call.
        noteCompressionRefused(region)
        Log.w(TAG, "gateway refused a gzip body; retrying in the clear for $region")
        return send(plain, false)
    }

    /** Compresses [text] when that would help, and passes it through otherwise. */
    fun encode(text: String): Encoded {
        val raw = text.toByteArray(Charsets.UTF_8)
        if (raw.size < MIN_BYTES) return Encoded(raw, false)
        val deflated = runCatching {
            ByteArrayOutputStream(raw.size / 8 + 64).use { sink ->
                LeveledGzip(sink, 8192, LEVEL).use { gz -> gz.write(raw) }
                sink.toByteArray()
            }
        }.getOrNull() ?: return Encoded(raw, false)
        // A body that did not shrink would only gain framing and an extra header.
        return if (deflated.size < raw.size) Encoded(deflated, true) else Encoded(raw, false)
    }

    /** A body ready for the wire, and whether it was compressed. */
    class Encoded(val bytes: ByteArray, val compressed: Boolean)

    /**
     * Whether a response means the request body itself could not be read.
     *
     * Only the statuses that say so are considered: a 401 or a 429 travels the
     * same path but says nothing about the body, and retrying a rate limit would
     * double the load that caused it. The gateway wraps every failure it raises
     * on its own in an envelope carrying `code` and `msg`, so a rejection without
     * one came from something in front of the application — which is what an
     * unreadable body looks like.
     */
    fun looksLikeRejectedEncoding(status: Int, body: String): Boolean {
        if (status != 400 && status != 415 && status != 422) return false
        val payload = runCatching { JSONObject(body) }.getOrNull() ?: return true
        return !payload.has("code") && !payload.has("msg") && !payload.has("displayMsg")
    }

    // ------------------------------------------------------------------ //
    // Learned capability
    // ------------------------------------------------------------------ //

    /**
     * Regions whose gateway has refused a compressed body.
     *
     * Compression is verified against the domestic gateway. A region that turns
     * out not to accept it is remembered here instead of being assumed, and the
     * set is shared between the endpoint and the built-in chat because they reach
     * the same gateways.
     */
    private val refusing: MutableSet<Wire.Region> = mutableSetOf()

    /** Whether [region]'s gateway should be sent plain bodies. */
    fun regionRefuses(region: Wire.Region): Boolean = synchronized(refusing) { region in refusing }

    /** Records that [region]'s gateway did not accept a compressed body. */
    fun noteCompressionRefused(region: Wire.Region) {
        synchronized(refusing) { refusing += region }
    }

    // ------------------------------------------------------------------ //
    // Encoder
    // ------------------------------------------------------------------ //

    /**
     * Smallest body worth compressing.
     *
     * Below this, the gzip framing and the CPU are larger than anything saved,
     * and a short request is not what makes a caller wait.
     */
    private const val MIN_BYTES = 1024

    /**
     * Compression effort.
     *
     * The lowest level is chosen deliberately. On this data the difference
     * between the fastest and the slowest setting is a few more kilobytes off a
     * result that is already a single packet, while the slowest setting costs
     * several times the CPU — and that CPU is paid before the request leaves the
     * device.
     */
    private const val LEVEL = 1

    /**
     * A gzip stream whose effort can be chosen.
     *
     * [GZIPOutputStream] keeps its deflater private, and the default effort is
     * the slowest one — which matters here because the CPU is spent before the
     * request leaves the device, while the bytes it saves are a rounding error on
     * a body that is already a single packet.
     */
    private class LeveledGzip(out: OutputStream, size: Int, level: Int) :
        GZIPOutputStream(out, size) {
        init {
            def.setLevel(level)
        }
    }
}
