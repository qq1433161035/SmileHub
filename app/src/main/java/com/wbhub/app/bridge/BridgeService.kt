package com.wbhub.app.bridge

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.ProtoModel
import com.wbhub.app.proto.UpstreamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hosts the loopback bridge in the foreground so the system keeps serving it.
 *
 * The foreground promotion is not optional: without it the process is a plain
 * background service and aggressive OEM policies freeze it within minutes,
 * which shows up as requests that never answer. Promotion failure is therefore
 * recorded in [foreground] rather than swallowed, so the UI can say the bridge
 * is running but not protected.
 */
class BridgeService : Service() {

    inner class LocalBinder : Binder() {
        fun service(): BridgeService = this@BridgeService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var models: List<String> = emptyList()

    /**
     * The full catalogue rows, kept so a recorded call can carry the price of
     * the model it used. The id list above is what the bridge advertises; this
     * is the same fetch with the figures retained.
     */
    private var catalogue: List<ProtoModel> = emptyList()
    private val upstream = UpstreamClient()
    private lateinit var store: CredentialStore
    private var bridge: BridgeServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var overlay: BridgeOverlay? = null
    private lateinit var callLog: CallLogStore

    /** Loopback port the bridge serves. */
    var port: Int = DEFAULT_PORT
        private set

    /** Key clients must present; editable from the settings screen. */
    private var apiKey: String = BridgeSettings.DEFAULT_API_KEY

    /** Whether the process is protected by a foreground notification. */
    @Volatile
    var foreground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        store = CredentialStore(this)
        callLog = CallLogStore(this)
        Notifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The intent value wins when it is present, so an explicit start from
        // the settings screen takes effect; the stored port is the fallback for
        // a service the system restarts on its own.
        port = intent?.getIntExtra(EXTRA_PORT, 0)?.takeIf { it > 0 }
            ?: BridgeSettings.port(this)
        apiKey = BridgeSettings.apiKey(this)
        promoteToForeground()
        acquireWakeLock()
        BridgeStatus.reset(port)
        showOverlay()

        if (bridge == null) {
            bridge = BridgeServer(
                port = port,
                secret = apiKey,
                lanKey = BridgeSettings.lanKey(this),
                credential = { store.resolve { c -> upstream.refreshToken(c) } },
                models = { models },
                multiplierOf = { id -> catalogue.firstOrNull { it.id == id }?.multiplier ?: -1.0 },
                // Read from settings rather than the intent: toggling LAN access
                // restarts the service, and the flag is one of the things it
                // must pick up.
                lanEnabled = BridgeSettings.lanEnabled(this),
                aggregateNonStream = BridgeSettings.aggregateNonStream(this),
                anthropicEnabled = BridgeSettings.formatAnthropic(this),
                responsesEnabled = BridgeSettings.formatResponses(this),
                disguiseIdentity = BridgeSettings.disguiseIdentity(this),
                httpsEnabled = BridgeSettings.httpsEnabled(this),
                certificate = { tlsCertificate() },
                onCall = { record -> callLog.append(record) },
            ).also { it.start() }
            scope.launch { refreshModelsLoop() }
        }
        return START_STICKY
    }

    /**
     * Requests the foreground state. From a background caller Android 12+
     * refuses, so the outcome is captured instead of thrown; the UI reports it.
     */
    private fun promoteToForeground() {
        foreground = runCatching {
            startForeground(Notifications.bridgeId(), Notifications.buildBridge(this, port))
            true
        }.onFailure {
            android.util.Log.e("WBHub", "startForeground refused; bridge is unprotected", it)
        }.getOrDefault(false)
    }

    /**
     * A partial wake lock keeps the CPU servicing the socket while the screen is
     * off. Held only while the bridge exists, so it is released in onDestroy.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = runCatching {
            (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WBHub:bridge")
                .apply { setReferenceCounted(false); acquire() }
        }.onFailure { android.util.Log.w("WBHub", "wake lock unavailable", it) }.getOrNull()
    }

    /**
     * Shows the status panel when the user granted overlay permission.
     *
     * The panel is what keeps the process at visible importance, so the system
     * does not freeze it while it serves requests. Without the permission the
     * bridge still runs; it is simply subject to the usual background policy.
     */
    private fun showOverlay() {
        if (overlay?.isShowing() == true) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            android.util.Log.i("WBHub", "overlay permission not granted; bridge runs without the panel")
            return
        }
        overlay = BridgeOverlay(
            context = this,
            onClose = { removeOverlay() },
            panelOpacity = BridgeSettings.opacity(this),
            locked = BridgeSettings.locked(this),
            stealth = BridgeSettings.stealthEnabled(this),
        ).also { overlay ->
            overlay.dotColor = BridgeSettings.dotColor(this)
            overlay.dotSize = BridgeSettings.dotSize(this)
            overlay.dotShape = BridgeSettings.dotShape(this)
            overlay.dotAlpha = BridgeSettings.dotAlpha(this)
            overlay.burnInEnabled = BridgeSettings.burnInEnabled(this)
            overlay.burnInIntervalMs = BridgeSettings.burnInIntervalMs(this)
            overlay.show()
        }
        overlayRef = overlay
    }

    /** Hides the panel; the service itself keeps running. */
    fun removeOverlay() {
        overlay?.hide()
        overlay = null
        overlayRef = null
    }

    private suspend fun refreshModelsLoop() {
        while (true) {
            val cred = store.active()
            if (cred != null) {
                val fetched = runCatching { upstream.fetchModels(cred) }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    catalogue = fetched
                    models = fetched.map { it.id }
                }
            }
            delay(MODEL_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The certificate to present, naming every address a client may dial.
     *
     * The names are decided here rather than in the server because only this
     * class knows whether the endpoint is reachable beyond the device: with LAN
     * access on, a peer dials the phone's own address, and a certificate that
     * named only loopback would fail that client's hostname check.
     */
    private fun tlsCertificate(): LocalCertificate.Material? {
        val hosts = buildList {
            add("localhost")
            add("127.0.0.1")
            add("::1")
            if (BridgeSettings.lanEnabled(this@BridgeService)) {
                addAll(LanAddresses.current())
            }
        }
        return LocalCertificate.load(this, hosts)
    }

    /**
     * Rebuilds the listener so a changed bind address takes effect.
     *
     * The socket cannot be re-bound in place, so the old one is dropped and a
     * new server started; the stored credential and call log are untouched.
     */
    fun restartBridge() {
        bridge?.stop()
        bridge = BridgeServer(
            port = port,
            secret = apiKey,
            lanKey = BridgeSettings.lanKey(this),
            credential = { store.resolve { c -> upstream.refreshToken(c) } },
            models = { models },
            multiplierOf = { id -> catalogue.firstOrNull { it.id == id }?.multiplier ?: -1.0 },
            lanEnabled = BridgeSettings.lanEnabled(this),
            aggregateNonStream = BridgeSettings.aggregateNonStream(this),
            anthropicEnabled = BridgeSettings.formatAnthropic(this),
            responsesEnabled = BridgeSettings.formatResponses(this),
            disguiseIdentity = BridgeSettings.disguiseIdentity(this),
            httpsEnabled = BridgeSettings.httpsEnabled(this),
            certificate = { tlsCertificate() },
            onCall = { record -> callLog.append(record) },
        ).also { it.start() }
        BridgeStatus.reset(port)
    }

    override fun onDestroy() {
        removeOverlay()
        BridgeStatus.stopped()
        bridge?.stop()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        /**
         * The live panel, so the settings screen can retune it without waiting
         * for the service to be recreated. Cleared when the panel is closed.
         */
        @Volatile
        var overlayRef: BridgeOverlay? = null
            private set

        const val DEFAULT_PORT = 8765
        const val DEFAULT_SECRET = "wb-local"
        const val EXTRA_PORT = "port"
        const val EXTRA_SECRET = "secret"
        private const val MODEL_REFRESH_MS = 5 * 60 * 1000L
    }
}
