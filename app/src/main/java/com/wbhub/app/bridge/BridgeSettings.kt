package com.wbhub.app.bridge

import android.content.Context
import com.wbhub.app.storage.AppStorage

/**
 * User preferences for the status panel.
 *
 * Kept in shared preferences rather than the credential file: these are
 * presentation choices that must survive a restart, and they are not secrets.
 */
object BridgeSettings {

    private const val PREFS = "wb-overlay"
    private const val KEY_OPACITY = "opacity"
    private const val KEY_LOCKED = "locked"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_LAN_KEY = "lan_key"
    private const val KEY_LAN = "lan_enabled"
    private const val KEY_HTTPS = "https_enabled"
    private const val KEY_AGGREGATE = "aggregate_non_stream"
    private const val KEY_FORMAT_ANTHROPIC = "format_anthropic"
    private const val KEY_DISGUISE = "disguise_client_identity"
    private const val KEY_FORMAT_RESPONSES = "format_responses"
    private const val KEY_STEALTH = "stealth_enabled"
    private const val KEY_OVERLAY = "overlay_enabled"
    private const val KEY_DOT_X = "dot_x"
    private const val KEY_DOT_Y = "dot_y"
    private const val KEY_DOT_COLOR = "dot_color"
    private const val KEY_DOT_SIZE = "dot_size"
    private const val KEY_DOT_SHAPE = "dot_shape"
    private const val KEY_DOT_ALPHA = "dot_alpha"
    private const val KEY_BURN_IN = "burn_in_enabled"
    private const val KEY_BURN_IN_INTERVAL = "burn_in_interval_ms"
    private const val KEY_CALL_LIMIT = "call_log_limit"
    private const val KEY_PORT = "port"

    /**
     * Whether the panel is replaced by a small dot.
     *
     * The dot exists so the process stays visible without the panel covering
     * whatever the user is doing; it answers nothing when tapped.
     */
    fun stealthEnabled(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_STEALTH, false)

    fun setStealthEnabled(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_STEALTH, value).apply()
    }

    /**
     * Whether the running bridge shows its floating panel.
     *
     * The panel exists to keep the process visible, so the system does not
     * freeze it; for a user who only calls the endpoint from other devices the
     * notification is enough and the panel just gets in the way.
     */
    fun overlayEnabled(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_OVERLAY, false)

    fun setOverlayEnabled(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_OVERLAY, value).apply()
    }

    /** Dot colour as a packed ARGB value. */
    fun dotColor(context: Context): Int =
        AppStorage.prefs(context, PREFS)
            .getInt(KEY_DOT_COLOR, DEFAULT_DOT_COLOR)

    fun setDotColor(context: Context, value: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_DOT_COLOR, value).apply()
    }

    /** Dot diameter in dp. */
    fun dotSize(context: Context): Int =
        AppStorage.prefs(context, PREFS).getInt(KEY_DOT_SIZE, DEFAULT_DOT_SIZE)

    fun setDotSize(context: Context, value: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_DOT_SIZE, value.coerceIn(MIN_DOT_SIZE, MAX_DOT_SIZE)).apply()
    }

    fun dotShape(context: Context): DotShape =
        DotShape.fromName(AppStorage.prefs(context, PREFS).getString(KEY_DOT_SHAPE, null))

    fun setDotShape(context: Context, value: DotShape) {
        AppStorage.prefs(context, PREFS)
            .edit().putString(KEY_DOT_SHAPE, value.name).apply()
    }

    /**
     * Dot opacity, 0..1.
     *
     * Zero is allowed and means the dot is invisible while still being a window:
     * that is the point, since the window is what keeps the process alive. A
     * floor above zero would force a visible mark on someone who does not want
     * one, and would make the setting unable to express that choice.
     */
    fun dotAlpha(context: Context): Float =
        AppStorage.prefs(context, PREFS)
            .getFloat(KEY_DOT_ALPHA, DEFAULT_DOT_ALPHA)

    fun setDotAlpha(context: Context, value: Float) {
        AppStorage.prefs(context, PREFS)
            .edit().putFloat(KEY_DOT_ALPHA, value.coerceIn(0f, 1f)).apply()
    }

    /**
     * Whether the dot cycles through neighbouring positions.
     *
     * A static shape lights the same pixels continuously, which on an OLED panel
     * is exactly the condition that leaves a permanent mark. Moving it by at
     * least its own diameter gives every pixel a turn being dark.
     */
    fun burnInEnabled(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_BURN_IN, true)

    fun setBurnInEnabled(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_BURN_IN, value).apply()
    }

    /** How long the dot stays in one position. */
    fun burnInIntervalMs(context: Context): Long =
        AppStorage.prefs(context, PREFS)
            .getLong(KEY_BURN_IN_INTERVAL, DEFAULT_BURN_IN_INTERVAL_MS)
            .coerceIn(MIN_BURN_IN_INTERVAL_MS, MAX_BURN_IN_INTERVAL_MS)

    fun setBurnInIntervalMs(context: Context, value: Long) {
        AppStorage.prefs(context, PREFS)
            .edit().putLong(
                KEY_BURN_IN_INTERVAL,
                value.coerceIn(MIN_BURN_IN_INTERVAL_MS, MAX_BURN_IN_INTERVAL_MS),
            ).apply()
    }

    /**
     * How many call records to keep.
     *
     * A user setting rather than a constant: the records are stored on the
     * device and their size is the user's to trade against how far back they
     * want to look.
     */
    fun callLogLimit(context: Context): Int =
        AppStorage.prefs(context, PREFS)
            .getInt(KEY_CALL_LIMIT, DEFAULT_CALL_LIMIT)
            .coerceIn(MIN_CALL_LIMIT, MAX_CALL_LIMIT)

    fun setCallLogLimit(context: Context, value: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_CALL_LIMIT, value.coerceIn(MIN_CALL_LIMIT, MAX_CALL_LIMIT)).apply()
    }

    /** A default that reads as "working" against most backgrounds. */
    const val DEFAULT_DOT_COLOR = 0xFF34C759.toInt()
    const val DEFAULT_DOT_SIZE = 12
    const val MIN_DOT_SIZE = 6
    const val MAX_DOT_SIZE = 40
    const val DEFAULT_DOT_ALPHA = 1f

    /** One second is enough to be a stress option; two minutes is the default. */
    const val MIN_BURN_IN_INTERVAL_MS = 1_000L
    const val MAX_BURN_IN_INTERVAL_MS = 30 * 60 * 1000L
    const val DEFAULT_BURN_IN_INTERVAL_MS = 2 * 60 * 1000L

    /**
     * Bounds on the call history.
     *
     * The upper end is what a few hundred kilobytes per thousand records buys:
     * large enough to look back weeks, small enough not to be a hidden cost.
     */
    const val MIN_CALL_LIMIT = 100
    const val MAX_CALL_LIMIT = 100_000
    const val DEFAULT_CALL_LIMIT = 500

    /**
     * 首次使用随机生成，不再用公开常量。
     *
     * 固定 `wb-local` 意味着同机任何应用、同网段任何设备都能直接调用并消耗
     * 账号额度；随机值会随设置页展示，用户复制到客户端即可。
     */
    fun apiKey(context: Context): String {
        val prefs = AppStorage.prefs(context, PREFS)
        prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = randomKey("wb-")
        prefs.edit().putString(KEY_API_KEY, generated).apply()
        return generated
    }

    fun setApiKey(context: Context, value: String) {
        val trimmed = value.trim().ifBlank { randomKey("wb-") }
        AppStorage.prefs(context, PREFS)
            .edit().putString(KEY_API_KEY, trimmed).apply()
    }

    private fun randomKey(prefix: String): String {
        val bytes = ByteArray(18).also { java.security.SecureRandom().nextBytes(it) }
        return prefix + android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
        )
    }

    /**
     * Port the bridge listens on.
     *
     * Persisted so a restart keeps the chosen port: a client configured against
     * it would otherwise break every time the app is reopened.
     */
    fun port(context: Context): Int = AppStorage.prefs(context, PREFS)
        .getInt(KEY_PORT, DEFAULT_PORT)
        .coerceIn(MIN_PORT, MAX_PORT)

    fun setPort(context: Context, value: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_PORT, value.coerceIn(MIN_PORT, MAX_PORT)).apply()
    }

    const val DEFAULT_PORT = 8765

    /** Ports below 1024 need privileges, above 65535 do not exist. */
    const val MIN_PORT = 1024
    const val MAX_PORT = 65535

    /**
     * The key peers on the local network must present.
     *
     * Separate from the local one on purpose: a key that travels over the
     * network is the one worth being able to change, and keeping it distinct
     * means revoking peer access does not disturb this device's own clients.
     */
    fun lanKey(context: Context): String {
        val prefs = AppStorage.prefs(context, PREFS)
        prefs.getString(KEY_LAN_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = randomKey("wbl-")
        prefs.edit().putString(KEY_LAN_KEY, generated).apply()
        return generated
    }

    fun setLanKey(context: Context, value: String) {
        val trimmed = value.trim().ifBlank { randomKey("wbl-") }
        AppStorage.prefs(context, PREFS)
            .edit().putString(KEY_LAN_KEY, trimmed).apply()
    }

    /**
     * Whether the endpoint is reachable from the local network.
     *
     * Off by default: the endpoint holds a credential, and loopback is the
     * only binding that needs no further trust decision.
     */
    fun lanEnabled(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_LAN, false)

    fun setLanEnabled(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_LAN, value).apply()
    }

    /**
     * Whether the endpoint also speaks TLS.
     *
     * Off by default. Plain HTTP on loopback is already private to the device,
     * and a client configured against `http://` keeps working either way — this
     * opens a second listener on the next port rather than changing the first.
     * It exists for clients that refuse a plain-text base URL, and for a peer on
     * the network, where plain HTTP puts the shared key on the wire.
     */
    fun httpsEnabled(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_HTTPS, false)

    fun setHttpsEnabled(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_HTTPS, value).apply()
    }

    /**
     * Whether a client asking for a single response gets one.
     *
     * The upstream has no non-streaming mode, so serving such a client means
     * collecting its stream here. That is off by default because the collection
     * holds the whole answer in memory and delays the first byte until the
     * generation finishes, which is a real cost for anyone who did not need it.
     */
    fun aggregateNonStream(context: Context): Boolean =
        AppStorage.prefs(context, PREFS)
            .getBoolean(KEY_AGGREGATE, false)

    /**
     * Whether the Anthropic Messages endpoint is served.
     *
     * Off by default: the endpoint translates a different request shape, and a
     * user who does not have a client speaking it gains nothing from it being
     * open.
     */
    fun formatAnthropic(context: Context): Boolean =
        AppStorage.prefs(context, PREFS)
            .getBoolean(KEY_FORMAT_ANTHROPIC, false)

    fun setFormatAnthropic(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_FORMAT_ANTHROPIC, value).apply()
    }

    /**
     * Whether a client's self-identifying system prompt is rewritten.
     *
     * The upstream refuses the identity passages several clients open their
     * instructions with — Claude Code's and Codex's among them — and a client
     * that sends one cannot be served at all. Rewriting them changes neither the
     * request's meaning nor the model's behaviour; it only removes the words the
     * upstream rejects.
     *
     * On by default, because every client this endpoint exists to serve sends
     * one: leaving it off makes the endpoint look broken for exactly those
     * clients, with nothing to say why. Leaving it on costs a client that sends
     * nothing of the sort a few string comparisons per message.
     */
    fun disguiseIdentity(context: Context): Boolean =
        AppStorage.prefs(context, PREFS)
            .getBoolean(KEY_DISGUISE, true)

    fun setDisguiseIdentity(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_DISGUISE, value).apply()
    }

    /** Whether the OpenAI Responses endpoint is served. */
    fun formatResponses(context: Context): Boolean =
        AppStorage.prefs(context, PREFS)
            .getBoolean(KEY_FORMAT_RESPONSES, false)

    fun setFormatResponses(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_FORMAT_RESPONSES, value).apply()
    }

    fun setAggregateNonStream(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_AGGREGATE, value).apply()
    }

    fun opacity(context: Context): Float =
        AppStorage.prefs(context, PREFS)
            .getFloat(KEY_OPACITY, 0.94f)

    fun setOpacity(context: Context, value: Float) {
        AppStorage.prefs(context, PREFS)
            .edit().putFloat(KEY_OPACITY, value.coerceIn(0.15f, 1f)).apply()
    }

    fun locked(context: Context): Boolean =
        AppStorage.prefs(context, PREFS).getBoolean(KEY_LOCKED, false)

    fun setLocked(context: Context, value: Boolean) {
        AppStorage.prefs(context, PREFS)
            .edit().putBoolean(KEY_LOCKED, value).apply()
    }

    fun position(context: Context): Pair<Int, Int> {
        val prefs = AppStorage.prefs(context, PREFS)
        return prefs.getInt(KEY_X, -1) to prefs.getInt(KEY_Y, -1)
    }

    fun setPosition(context: Context, x: Int, y: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    /**
     * Dot position, kept apart from the panel's.
     *
     * The two are different sizes and serve different purposes, so where the
     * dot is tucked away is rarely where the panel belongs; sharing one pair
     * would make switching modes move the other one.
     */
    fun dotPosition(context: Context): Pair<Int, Int> {
        val prefs = AppStorage.prefs(context, PREFS)
        return prefs.getInt(KEY_DOT_X, -1) to prefs.getInt(KEY_DOT_Y, -1)
    }

    fun setDotPosition(context: Context, x: Int, y: Int) {
        AppStorage.prefs(context, PREFS)
            .edit().putInt(KEY_DOT_X, x).putInt(KEY_DOT_Y, y).apply()
    }
}

/**
 * Reads the address a peer on the local network should use.
 *
 * Only the Wi-Fi interface counts, and only its IPv4 address is reported. A
 * mobile-data address belongs to the carrier's internal network and looks like a
 * private one, and a link-local IPv6 address needs a scope suffix that clients
 * read inconsistently, so neither is worth handing out.
 */
object LanAddresses {

    fun current(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { iface ->
                runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false) && isWifi(iface.name)
            }
            .flatMap { iface ->
                iface.interfaceAddresses
                    // An IPv4 literal is four dotted octets; anything else here
                    // is IPv6, which a peer would have to be told how to scope.
                    .mapNotNull { it.address?.hostAddress }
                    .filter { it.count { c -> c == '.' } == 3 }
            }
            .filter { it.isNotEmpty() && !it.startsWith("127.") }
            .distinct()
    }.getOrDefault(emptyList())

    /**
     * Interface names Android uses for Wi-Fi.
     *
     * A hotspot shows up under an `ap` or `swlan` name, and those are worth
     * reporting too: the device is then the network a peer joins.
     */
    private fun isWifi(name: String): Boolean =
        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
}

/** Shape of the stealth indicator. */
enum class DotShape(val label: String) {
    /** A filled disc: the default, reads as a status light. */
    FILLED("实心圆点"),

    /** A ring: lighter on the eye while still visible. */
    RING("空心圆圈"),
    ;

    companion object {
        fun fromName(value: String?): DotShape =
            entries.firstOrNull { it.name == value } ?: FILLED
    }
}
