package com.wbhub.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.wbhub.app.bridge.BridgeService
import com.wbhub.app.bridge.BridgeSettings
import com.wbhub.app.bridge.LanAddresses
import com.wbhub.app.bridge.CallLogStore
import com.wbhub.app.bridge.CallRecord
import com.wbhub.app.bridge.Notifications
import com.wbhub.app.data.CheckinItem
import com.wbhub.app.data.Login
import com.wbhub.app.proto.AutoTaskRunner
import com.wbhub.app.agent.AgentClient
import com.wbhub.app.agent.AgentStore
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.agent.ArtifactStore
import com.wbhub.app.agent.CompactionState
import com.wbhub.app.agent.ContextEngine
import com.wbhub.app.proto.CheckinOutcome
import com.wbhub.app.proto.Credential
import com.wbhub.app.proto.CredentialStore
import com.wbhub.app.proto.UpstreamClient
import com.wbhub.app.proto.Wire
import com.wbhub.app.proto.toHubModel
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.ToolingRuntime
import com.wbhub.app.ui.ToolingArtifact
import com.wbhub.app.ui.ToolingOperation
import com.wbhub.app.ui.ToolingProject
import com.wbhub.app.ui.ToolingProjectDetail
import com.wbhub.app.ui.AgentEntry
import com.wbhub.app.ui.AgentState
import com.wbhub.app.ui.CheckinDialog
import com.wbhub.app.ui.EFFORT_OFF
import com.wbhub.app.ui.HubApp
import com.wbhub.app.ui.Loading
import com.wbhub.app.ui.LogoutDialog
import com.wbhub.app.ui.HubState
import com.wbhub.app.ui.HubTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var store: CredentialStore
    private lateinit var callLog: CallLogStore
    private var service: BridgeService? = null
    private var bound by mutableStateOf(false)
    private var pollJob: Job? = null

    private var state by mutableStateOf(HubState())
    private var showHelp by mutableStateOf(false)

    /** The agent page keeps its own transcript and settings. */
    private var agent by mutableStateOf(AgentState())
    private var askedForNotifications = false
    private val upstream = UpstreamClient()

    /**
     * Daily bonus routine run after each check-in. It is idempotent upstream,
     * so re-running it on every check-in cannot duplicate rewards.
     */
    private val autoTask = AutoTaskRunner()

    private val agentClient = AgentClient()

    /** Settings and saved conversations for the agent page. */
    private lateinit var agentStore: AgentStore

    /**
     * The exchange so far, kept in the shape the model needs. The visible
     * transcript is a rendering of it, not a substitute: tool calls and their
     * results only exist here.
     */
    private var lastAgentTranscript: List<AgentClient.Message> = emptyList()

    /** 会话被压缩后的摘要状态，随会话一起落盘。 */
    private var lastCompaction = CompactionState()

    /** A login session survives the agent request, so it is not lifecycle-bound. */
    private val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 正在运行的对话，按会话 id 区分。
     *
     * 每个会话各自持有 AgentClient、transcript、压缩状态和可见条目：
     * 切到别的会话时后台继续跑，输出写回自己的条目，不再覆盖屏幕上的会话。
     */
    private class AgentRun(val id: String, var title: String) {
        val client = AgentClient()
        var transcript: List<AgentClient.Message> = emptyList()
        var compaction = CompactionState()
        var entries: List<AgentEntry> = emptyList()
        var pendingApproval: String? = null
        val answers = LinkedBlockingQueue<Boolean>()
    }

    private val agentRuns = mutableMapOf<String, AgentRun>()

    /** Delivers streamed agent events to the main thread. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Sign-in polling must survive the app leaving the foreground, because the
     * user completes it in the browser; a lifecycle-bound scope would be
     * suspended exactly when the poll needs to run.
     */
    private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? BridgeService.LocalBinder)?.service()
            bound = service != null
            state = state.copy(bridgeRunning = bound, port = service?.port ?: state.port)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            state = state.copy(bridgeRunning = false)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        state = state.copy(
            notificationsAllowed = granted,
            status = if (granted) "" else "未授予通知权限，转发服务可能被系统回收",
        )
        // The same launcher serves the agent's storage request on older
        // releases, so the switch is re-read rather than assumed.
        agent = agent.copy(storageGranted = hasAllFilesAccess())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStorage.init(this)
        store = CredentialStore(this)
        callLog = CallLogStore(this)
        agentStore = AgentStore(this)
        Notifications.ensureChannel(this)
        refreshCredential()
        // Keep the daemon's credential copy current, including the first run
        // after an account switch happened while it was not running.
        store.mirrorActiveForDaemon()
        loadBalance()
        loadModels()
        // 调用记录不在启动时读取：条目可能很多，用户进「个人信息」后按需加载。
        // Create /WBHub/tools and register external tools before the first
        // prompt, so editing a script does not require opening the 工程 page.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { ToolingRuntime.get(this@MainActivity) }
        }
        state = state.copy(
            notificationsAllowed = Notifications.hasPermission(this),
            overlayOpacity = BridgeSettings.opacity(this),
            overlayLocked = BridgeSettings.locked(this),
        )
        val lanOn = BridgeSettings.lanEnabled(this)
        state = state.copy(
            port = BridgeSettings.port(this),
            secret = BridgeSettings.apiKey(this),
            lanKey = BridgeSettings.lanKey(this),
            lanEnabled = lanOn,
            aggregateNonStream = BridgeSettings.aggregateNonStream(this),
            formatAnthropic = BridgeSettings.formatAnthropic(this),
            formatResponses = BridgeSettings.formatResponses(this),
            httpsEnabled = BridgeSettings.httpsEnabled(this),
            disguiseIdentity = BridgeSettings.disguiseIdentity(this),
            lanAddresses = if (lanOn) LanAddresses.current() else emptyList(),
            stealthEnabled = BridgeSettings.stealthEnabled(this),
            dotColor = BridgeSettings.dotColor(this),
            dotSize = BridgeSettings.dotSize(this),
            dotShape = BridgeSettings.dotShape(this),
            dotAlpha = BridgeSettings.dotAlpha(this),
            burnInEnabled = BridgeSettings.burnInEnabled(this),
            burnInIntervalMs = BridgeSettings.burnInIntervalMs(this),
            overlayEnabled = BridgeSettings.overlayEnabled(this),
        )
        // Restored before the model catalogue arrives; a stored model that no
        // longer exists is replaced once the catalogue does.
        val activeId = agentStore.activeSessionId()
        val restored = activeId.takeIf { it.isNotEmpty() }?.let { agentStore.load(it) }
        lastAgentTranscript = restored?.messages ?: emptyList()
        lastCompaction = restored?.compaction ?: CompactionState()
        agent = agent.copy(
            storageGranted = hasAllFilesAccess(),
            modelId = agentStore.modelId(),
            effort = agentStore.effort(),
            contextWindow = agentStore.contextWindow(),
            exposeRoot = agentStore.exposeRoot(),
            workDir = agentStore.workDir().ifEmpty { agent.workDir },
            approval = agentStore.approval(),
            maxRounds = agentStore.maxRounds(),
            compactionEnabled = agentStore.compactionEnabled(),
            compactTrigger = agentStore.compactTrigger(),
            compactKeepTurns = agentStore.compactKeepTurns(),
            offloadChars = agentStore.offloadChars(),
            sessions = agentStore.sessions(),
            sessionId = restored?.id.orEmpty(),
            sessionTitle = restored?.title.orEmpty(),
            entries = restored?.entries?.mapNotNull { entry ->
                val role = runCatching { AgentEntry.Role.valueOf(entry.role) }.getOrNull()
                    ?: return@mapNotNull null
                AgentEntry(role, entry.text)
            }.orEmpty(),
        )
        // Root is probed rather than assumed: the switch is meaningless on a
        // device that never grants it.
        checkRootAvailability()
        // The endpoint is off by default: it exists for other devices to call,
        // and starting it behind the user's back also forces a floating panel.

        setContent {
            HubApp(
                state = state,
                onStartBridge = { port ->
                    // Persisted before starting, so a restart lands on the same
                    // port the client was configured against.
                    BridgeSettings.setPort(this, port)
                    state = state.copy(port = BridgeSettings.port(this))
                    startBridge(state.port)
                },
                onStopBridge = { stopBridge() },
                onLogin = { region -> startLogin(region) },
                onLogout = { state = state.copy(showLogoutConfirm = true) },
                onClearCalls = { clearCalls() },
                onOpenCallSettings = {
                    // 设置页要显示占用大小，打开时按需读一次。
                    if (!state.callsLoaded) loadCalls()
                    state = state.copy(showCallSettings = true)
                },
                onOpenModelStats = {
                    if (!state.callsLoaded) loadCalls()
                    state = state.copy(showModelStats = true)
                },
                onDismissModelStats = { state = state.copy(showModelStats = false) },
                onDismissCallSettings = { state = state.copy(showCallSettings = false) },
                onCallLimitChange = { limit ->
                    BridgeSettings.setCallLogLimit(this, limit)
                    // Trimming now rather than at the next call, so the size
                    // shown reflects the limit that was just chosen.
                    callLog.trimToLimit()
                    state = state.copy(
                        callLogLimit = BridgeSettings.callLogLimit(this),
                        calls = callLog.load(),
                        callLogBytes = callLog.estimatedBytes(BridgeSettings.callLogLimit(this)),
                    )
                },
                onRefreshCalls = { loadCalls() },
                onOverlayOpacity = { value ->
                    BridgeSettings.setOpacity(this, value)
                    // Applied to the live panel, so the slider is visible as it moves.
                    BridgeService.overlayRef?.panelOpacity = value
                    state = state.copy(overlayOpacity = value)
                },
                onOverlayLocked = { locked ->
                    BridgeSettings.setLocked(this, locked)
                    BridgeService.overlayRef?.locked = locked
                    state = state.copy(overlayLocked = locked)
                },
                onSwitchRealm = { region -> switchRealm(region) },
                onSwitchAccount = { id -> switchAccount(id) },
                onDeleteAccount = { id -> deleteAccount(id) },
                onRequestOverlay = { requestOverlayPermission() },
                onOpenCredentialDetails = { state = state.copy(showCredentialDrawer = true) },
                onDismissCredentialDetails = { state = state.copy(showCredentialDrawer = false) },
                onCopyField = { label, value -> copyToClipboard(value, "已复制 $label") },
                onCheckin = { doCheckin() },
                onCheckinAll = { checkinAllAccounts() },
                onRefreshBalance = { loadBalance() },
                onSaveKey = { key ->
                    BridgeSettings.setApiKey(this, key)
                    state = state.copy(secret = BridgeSettings.apiKey(this))
                    // The listener holds the key it started with, so it has to
                    // be rebuilt for the change to apply.
                    service?.restartBridge()
                    toast("已更新密钥")
                },
                onToggleLan = { enabled ->
                    BridgeSettings.setLanEnabled(this, enabled)
                    state = state.copy(
                        lanEnabled = enabled,
                        lanAddresses = if (enabled) LanAddresses.current() else emptyList(),
                    )
                    service?.restartBridge()
                },
                onOpenBridgeAdvanced = { state = state.copy(showBridgeAdvanced = true) },
                onDismissBridgeAdvanced = { state = state.copy(showBridgeAdvanced = false) },
                onToggleAggregate = { enabled ->
                    BridgeSettings.setAggregateNonStream(this, enabled)
                    state = state.copy(aggregateNonStream = enabled)
                    // The listener reads the flag at start, so it is rebuilt.
                    service?.restartBridge()
                },
                onToggleAnthropic = { enabled ->
                    BridgeSettings.setFormatAnthropic(this, enabled)
                    state = state.copy(formatAnthropic = enabled)
                    // Turning it on is the moment the user is likely to be using
                    // Claude Code, which is the client the upstream refuses; the
                    // offer is made here rather than buried in the settings.
                    if (enabled && !BridgeSettings.disguiseIdentity(this)) {
                        state = state.copy(showDisguisePrompt = true)
                    }
                    service?.restartBridge()
                },
                onToggleDisguise = { enabled ->
                    BridgeSettings.setDisguiseIdentity(this, enabled)
                    state = state.copy(disguiseIdentity = enabled, showDisguisePrompt = false)
                    service?.restartBridge()
                },
                onDismissDisguisePrompt = { state = state.copy(showDisguisePrompt = false) },
                onToggleResponses = { enabled ->
                    BridgeSettings.setFormatResponses(this, enabled)
                    state = state.copy(formatResponses = enabled)
                    service?.restartBridge()
                },
                onToggleHttps = { enabled ->
                    BridgeSettings.setHttpsEnabled(this, enabled)
                    state = state.copy(httpsEnabled = enabled)
                    service?.restartBridge()
                },
                onSaveLanKey = { key ->
                    BridgeSettings.setLanKey(this, key)
                    state = state.copy(lanKey = BridgeSettings.lanKey(this))
                    service?.restartBridge()
                    toast("已更新局域网密钥")
                },
                onToggleStealth = { enabled ->
                    BridgeSettings.setStealthEnabled(this, enabled)
                    state = state.copy(stealthEnabled = enabled)
                    // The window's touchability is fixed at add time, so the
                    // overlay rebuilds itself.
                    BridgeService.overlayRef?.stealthMode = enabled
                },
                onToggleOverlay = { enabled ->
                    BridgeSettings.setOverlayEnabled(this, enabled)
                    state = state.copy(overlayEnabled = enabled)
                    service?.applyOverlaySetting()
                },
                onDotColor = { color ->
                    BridgeSettings.setDotColor(this, color)
                    state = state.copy(dotColor = color)
                    applyDotAppearance()
                },
                onDotSize = { size ->
                    BridgeSettings.setDotSize(this, size)
                    state = state.copy(dotSize = BridgeSettings.dotSize(this))
                    applyDotAppearance()
                },
                onDotShape = { shape ->
                    BridgeSettings.setDotShape(this, shape)
                    state = state.copy(dotShape = shape)
                    applyDotAppearance()
                },
                onDotAlpha = { alpha ->
                    BridgeSettings.setDotAlpha(this, alpha)
                    state = state.copy(dotAlpha = alpha)
                    applyDotAppearance()
                },
                onBurnIn = { enabled ->
                    BridgeSettings.setBurnInEnabled(this, enabled)
                    state = state.copy(burnInEnabled = enabled)
                    applyBurnIn()
                },
                onBurnInInterval = { interval ->
                    BridgeSettings.setBurnInIntervalMs(this, interval)
                    state = state.copy(burnInIntervalMs = BridgeSettings.burnInIntervalMs(this))
                    applyBurnIn()
                },
                onAdjustDot = { adjusting ->
                    state = state.copy(adjustingDot = adjusting)
                    // The dot's touchability is a window flag, so it has to be
                    // re-added for the change to apply.
                    BridgeService.overlayRef?.adjusting = adjusting
                },
                onStreakBonus = { runStreakBonus() },
                onActivityReport = { runActivityReport() },
                onTravel = { runTravel() },
                onNightOwl = { runNightOwl() },
                onAgentInput = { value -> agent = agent.copy(input = value) },
                onAgentSend = { sendAgentPrompt() },
                onAgentImagePicked = { dataUrl -> agent = agent.copy(pendingImage = dataUrl) },
                onAgentSelectModel = { id ->
                    // Switching models resets the effort and context: the
                    // accepted levels and lengths differ per model, so keeping
                    // the old values could send unsupported ones.
                    val model = state.models.firstOrNull { it.id == id }
                    agentStore.setModelId(id)
                    val effort = model?.defaultEffort.orEmpty()
                    val context = model?.contextWindow ?: 0
                    agentStore.setEffort(effort)
                    agentStore.setContextWindow(context)
                    agent = agent.copy(modelId = id, effort = effort, contextWindow = context)
                },
                onAgentSelectEffort = { level ->
                    agentStore.setEffort(level)
                    agent = agent.copy(effort = level)
                },
                onAgentSelectContext = { tokens ->
                    agentStore.setContextWindow(tokens)
                    agent = agent.copy(contextWindow = tokens)
                },
                onAgentToggleRoot = { enabled ->
                    agentStore.setExposeRoot(enabled)
                    agent = agent.copy(exposeRoot = enabled)
                },
                onAgentSelectApproval = { mode ->
                    agentStore.setApproval(mode)
                    agent = agent.copy(approval = mode)
                },
                onAgentSelectMaxRounds = { rounds ->
                    agentStore.setMaxRounds(rounds)
                    agent = agent.copy(maxRounds = rounds)
                },
                onAgentToggleCompaction = { enabled ->
                    agentStore.setCompactionEnabled(enabled)
                    agent = agent.copy(compactionEnabled = enabled)
                },
                onAgentSelectCompactTrigger = { percent ->
                    agentStore.setCompactTrigger(percent)
                    agent = agent.copy(compactTrigger = percent)
                },
                onAgentSelectCompactKeep = { turns ->
                    agentStore.setCompactKeepTurns(turns)
                    agent = agent.copy(compactKeepTurns = turns)
                },
                onAgentSelectOffloadChars = { chars ->
                    agentStore.setOffloadChars(chars)
                    agent = agent.copy(offloadChars = chars)
                },
                onAgentCompactNow = { compactAgentNow() },
                onAgentAnswerApproval = { allow -> answerApproval(allow) },
                onAgentStop = { stopAgent() },
                onAgentRequestStorage = { requestAllFilesAccess() },
                onAgentNewSession = { newAgentSession() },
                onAgentOpenSession = { id -> openAgentSession(id) },
                onAgentDeleteSession = { id -> deleteAgentSession(id) },
                agent = agent,
                onCopyEndpoint = { copyEndpoint() },
                onCopyModel = { id -> copyToClipboard(id, "已复制模型名") },
                onRequestNotifications = { requestNotificationPermissionIfNeeded() },
                onRefreshModels = { loadModels() },
                onRequestBatteryExemption = { requestBatteryExemption() },
                onDismissCheckin = { state = state.copy(checkinMessage = "") },
                showHelp = showHelp,
                onShowHelp = { showHelp = true },
                onDismissHelp = { showHelp = false },
                onOpenTooling = { openTooling() },
                onCloseTooling = { closeTooling() },
                onRefreshTooling = { refreshTooling() },
                onSelectToolingProject = { id -> selectToolingProject(id) },
                onDeleteToolingProject = { id -> deleteToolingProject(id) },
                onTabShown = { tab ->
                    // 余额随账号变化，进页面时拉一次；调用记录改成手动加载。
                    if (tab == HubTab.Personal) loadBalance()
                },
            )
        }

        requestNotificationPermissionIfNeeded()
        // The agent's shell works on real files, so the storage switch is asked
        // for up front rather than only when the agent page is first opened.
        if (!hasAllFilesAccess()) mainHandler.postDelayed({ requestAllFilesAccess() }, 800)
    }

    /**
     * Saves the conversation when the app leaves the foreground.
     *
     * This runs even while a turn is streaming: the visible transcript is worth
     * keeping, and the model-facing transcript already holds the completed
     * turns. A background kill otherwise loses everything since the last run.
     */
    override fun onStop() {
        super.onStop()
        persistAgentSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Tapping the login notification returns here; make sure the app shows
        // the credential it just acquired.
        if (intent.getBooleanExtra(Notifications.EXTRA_OPEN_AFTER_LOGIN, false)) {
            refreshCredential()
            showHelp = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Sign-in finishes in the browser, so the credential on disk is newer
        // than whatever this instance loaded; re-read it on every return.
        refreshCredential()
        state = state.copy(
            batteryExempt = isIgnoringBatteryOptimizations(),
            overlayAllowed = canDrawOverlay(),
        )
        // Storage access is granted on a system page, so the switch is only
        // accurate after coming back from it.
        agent = agent.copy(storageGranted = hasAllFilesAccess())
    }

    /**
     * Asks for POST_NOTIFICATIONS once per process. Launching from onCreate would
     * race the first composition, and re-asking on every launch is pointless
     * once the user has answered, so a denial is recorded instead of retried.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (Notifications.hasPermission(this)) return
        if (askedForNotifications) {
            state = state.copy(status = "如需后台保活，请在系统设置中允许通知")
            return
        }
        askedForNotifications = true
        permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onDestroy() {
        if (bound) runCatching { unbindService(connection) }
        pollJob?.cancel()
        super.onDestroy()
    }

    /** Re-reads the account in use and every build's saved accounts. */
    private fun refreshCredential() {
        val region = store.activeRegion()
        val account = store.activeAccount()
        state = state.copy(
            realm = region,
            credential = account?.toCredential(),
            expiryText = account?.let { formatExpiry(it.toCredential()) } ?: "",
            accounts = Wire.Region.entries.associateWith { store.accounts(it) },
            activeAccountId = account?.id,
        )
    }

    /** Switches to another saved account inside the current build. */
    private fun switchAccount(accountId: String) {
        val region = state.realm
        if (store.accounts(region).none { it.id == accountId }) return
        store.selectAccount(region, accountId)
        refreshCredential()
        loadModels()
        loadBalance()
        state = state.copy(status = "已切换账号")
    }

    /** Removes one saved account. */
    private fun deleteAccount(accountId: String) {
        val region = state.realm
        store.delete(region, accountId)
        refreshCredential()
        loadModels()
        loadBalance()
        state = state.copy(status = "已删除账号")
    }

    /**
     * Switches which build's credential the bridge serves. Each build keeps its
     * own stored credential, so the switch is instant and needs no re-login;
     * models and balance are reloaded because they belong to the account.
     */
    private fun switchRealm(region: Wire.Region) {
        store.setActiveRegion(region)
        refreshCredential()
        loadModels()
        loadBalance()
        state = state.copy(status = "已切换到${regionLabel(region)}账号")
    }

    private fun startBridge(port: Int, silent: Boolean = false) {
        requestNotificationPermissionIfNeeded()
        val intent = Intent(this, BridgeService::class.java).apply {
            putExtra(BridgeService.EXTRA_PORT, port)
            putExtra(BridgeService.EXTRA_SECRET, state.secret)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            state = state.copy(status = "服务启动失败：${it.message?.take(60)}")
            if (!silent) toast("服务启动失败")
            return
        }
        runCatching { bindService(intent, connection, Context.BIND_AUTO_CREATE) }
        state = state.copy(bridgeRunning = true, port = port, status = "")
        loadModels()
    }

    private fun stopBridge() {
        runCatching { unbindService(connection) }
        stopService(Intent(this, BridgeService::class.java))
        state = state.copy(bridgeRunning = false, status = "服务已停止")
    }

    /**
     * Runs [block] with [Loading] shown, keeping the indicator on screen for at
     * least [MIN_LOADING_MS]. Upstream answers in well under a second, and a
     * flash that short reads as "nothing happened".
     */
    private suspend fun <T> withLoading(kind: Loading, block: suspend () -> T): T {
        state = state.copy(loading = kind)
        val started = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < MIN_LOADING_MS) delay(MIN_LOADING_MS - elapsed)
            state = state.copy(loading = null)
        }
    }

    private fun loadModels() {
        val cred = state.credential ?: return
        lifecycleScope.launch {
            val models = withContext(Dispatchers.IO) {
                withLoading(Loading.MODELS) {
                    runCatching { upstream.fetchModels(cred).map { it.toHubModel() } }.getOrDefault(emptyList())
                }
            }
            state = state.copy(models = models)
            // The agent needs a model chosen before it can send anything. The
            // first catalogue row is a working default, and an existing choice
            // is kept so a refresh does not reset it.
            // A stored model that the catalogue no longer carries is replaced by
            // the first one; either way the choice is written back so a restart
            // keeps showing the same model.
            if (agent.modelId.isBlank() || models.none { it.id == agent.modelId }) {
                val first = models.firstOrNull()
                val effort = first?.defaultEffort.orEmpty()
                val context = first?.contextWindow ?: 0
                agentStore.setModelId(first?.id.orEmpty())
                agentStore.setEffort(effort)
                agentStore.setContextWindow(context)
                agent = agent.copy(
                    modelId = first?.id.orEmpty(),
                    effort = effort,
                    contextWindow = context,
                )
            }
        }
    }

    private fun startLogin(region: Wire.Region) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) {
                runCatching { Login.start(region.toLoginRealm()) }.getOrNull()
            }
            if (session == null) {
                toast("获取登录链接失败")
                return@launch
            }
            state = state.copy(status = "请在浏览器完成登录：${session.authUrl}")
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(session.authUrl)))
            pollForToken(session.state, region.toLoginRealm())
        }
    }

    private fun pollForToken(stateId: String, realm: Login.Realm) {
        pollJob?.cancel()
        pollJob = loginScope.launch {
            val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                val result = withContext(Dispatchers.IO) { runCatching { Login.poll(stateId, realm) }.getOrNull() }
                when (result) {
                    is Login.Poll.Done -> {
                        // The slot is the version the user signed in to: it is
                        // the only reliable statement of which account system
                        // this credential belongs to, and it is what the
                        // switcher displays.
                        val target = realm.toRegion()
                        store.save(target, result.credential)
                        store.setActiveRegion(target)
                        // Re-read from disk so the expiry goes through the same
                        // normalisation as a cold start: the login response can
                        // omit expiresAt, leaving the JWT claim as the only source.
                        refreshCredential()
                        loadModels()
                        loadBalance()
                        Notifications.showLoginSuccess(this@MainActivity, result.credential.nickname)
                        toast("凭证已保存")
                        return@launch
                    }
                    is Login.Poll.Failed -> {
                        state = state.copy(status = "登录失败：${result.message}")
                        return@launch
                    }
                    else -> delay(POLL_INTERVAL_MS)
                }
            }
            state = state.copy(status = "登录超时，请重试")
        }
    }

    /**
     * Checks in every account of the current build, one after another, and
     * reports each outcome separately.
     *
     * Accounts run sequentially on purpose: the upstream is rate-limited, and a
     * burst of concurrent check-ins would make some of them fail for reasons
     * that have nothing to do with the account itself.
     */
    private fun checkinAllAccounts() {
        val region = state.realm
        val targets = state.accounts[region].orEmpty()
        if (targets.isEmpty()) {
            state = state.copy(checkinMessage = "${regionLabel(region)}还没有账号")
            return
        }
        // With a single account the long press would do exactly what a tap does,
        // so say so rather than repeating the same work behind a dialog.
        if (targets.size == 1) {
            doCheckin()
            return
        }
        lifecycleScope.launch {
            val results: List<CheckinItem> = withContext(Dispatchers.IO) {
                withLoading(Loading.CHECKIN) {
                    targets.map { account ->
                        val outcome = runCatching { upstream.checkin(account.toCredential()) }
                            .getOrElse { CheckinOutcome(false, it.message ?: "签到失败") }
                        CheckinItem(label = account.label, ok = outcome.ok, message = outcome.message)
                    }
                }
            }
            state = state.copy(
                checkinItems = results,
                checkinMessage = "已为 ${results.size} 个账号完成签到",
            )
            loadBalance()
        }
    }

    private fun doCheckin() {
        val cred = state.credential
        if (cred == null) {
            state = state.copy(checkinMessage = "请先登录获取凭证")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                withLoading(Loading.CHECKIN) {
                    runCatching { upstream.checkin(cred) }
                        .getOrElse { CheckinOutcome(false, it.message ?: "签到失败") }
                }
            }
            state = state.copy(checkinMessage = result.message)
            loadBalance()
        }
    }

    // ------------------------------------------------------------------ //
    // Bonus routines
    //
    // Each runs on its own button rather than riding along with the check-in:
    // they hit different endpoints, some only work inside a time window, and a
    // failure in one should not make a check-in look unsuccessful.
    // ------------------------------------------------------------------ //

    /** Runs one named routine against every account of the active build. */
    private fun runBonus(label: String, kind: Loading, routine: (Credential) -> AutoTaskRunner.Result) {
        val region = state.realm
        val targets = state.accounts[region].orEmpty()
        if (targets.isEmpty()) {
            state = state.copy(checkinMessage = "${regionLabel(region)}还没有账号", checkinItems = emptyList())
            return
        }
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                withLoading(kind) {
                    targets.map { account ->
                        val result = runCatching { routine(account.toCredential()) }
                            .getOrElse { AutoTaskRunner.Result(listOf(AutoTaskRunner.Step(label, false, it.message ?: "失败"))) }
                        CheckinItem(label = account.label, ok = result.steps.all { it.ok }, message = result.summary)
                    }
                }
            }
            state = state.copy(checkinItems = items, checkinMessage = "$label 已完成（${items.size} 个账号）")
            loadBalance()
        }
    }

    private fun runStreakBonus() = runBonus("连登管家", Loading.STREAK) { autoTask.runStreakBonus(it) }

    private fun runActivityReport() = runBonus("活跃上报", Loading.ACTIVITY) { credential ->
        AutoTaskRunner.Result(listOf(autoTask.reportActivity(credential)))
    }

    private fun runTravel() = runBonus("猫猫旅行", Loading.TRAVEL) { autoTask.runTravel(it) }

    private fun runNightOwl() = runBonus("夜猫子", Loading.NIGHT) { autoTask.runNightOwl(it) }

    // ------------------------------------------------------------------ //
    // Agent
    // ------------------------------------------------------------------ //

    /**
     * Sends one prompt to the agent and streams the answer into the transcript.
     *
     * The whole loop runs on a background dispatcher; the transcript is only
     * touched on the main thread, one event at a time.
     */
    private fun sendAgentPrompt() {
        val credential = state.credential
        if (credential == null) {
            agent = agent.copy(entries = agent.entries + AgentEntry(AgentEntry.Role.ERROR, "请先在「凭证」页登录"))
            return
        }
        if (agent.modelId.isBlank()) {
            agent = agent.copy(entries = agent.entries + AgentEntry(AgentEntry.Role.ERROR, "请先选择模型"))
            return
        }
        val prompt = agent.input.trim()
        val promptImage = agent.pendingImage
        if (prompt.isEmpty() && promptImage == null) return
        // 只发图时给模型一句最小说明，避免某些上游拒绝空文本。
        val question = prompt.ifEmpty { "请看这张图片。" }
        // 同一会话一次只跑一条；别的会话可以并行，各自往自己的条目里写。
        if (agent.sessionId.isNotEmpty() && agentRuns.containsKey(agent.sessionId)) return

        val workDir = File(agent.workDir).apply { if (!exists()) mkdirs() }
        // A conversation is named after its first prompt, and gets its id at the
        // same moment, so the picker has something to show before it finishes.
        val sessionId = agent.sessionId.ifEmpty { "s${System.currentTimeMillis()}" }
        val title = agent.sessionTitle.ifEmpty { agentStore.titleFor(question) }
        val run = AgentRun(sessionId, title).apply {
            entries = agent.entries + AgentEntry(
                AgentEntry.Role.USER,
                if (promptImage != null) "$question\n（附图）" else question,
            )
            transcript = agentHistory()
            compaction = lastCompaction
        }
        agentRuns[sessionId] = run
        val modelId = agent.modelId
        val effort = agent.effort.takeIf { it.isNotBlank() }?.takeIf { it != EFFORT_OFF }
        val approval = agent.approval
        val maxRounds = agent.maxRounds
        val contextWindow = agent.contextWindow
        val tools = AgentTools(
            context = this,
            workDir = workDir,
            useRoot = agent.exposeRoot,
            tooling = ToolingRuntime.get(this).executor,
            sessionId = sessionId,
        )
        agent = agent.copy(
            input = "",
            pendingImage = null,
            running = true,
            sessionId = sessionId,
            sessionTitle = title,
            entries = run.entries,
            pendingApproval = null,
        )

        agentScope.launch {
            val contextEngine = ContextEngine(
                artifacts = ArtifactStore(this@MainActivity),
                config = ContextEngine.CompressionConfig(
                    enabled = agent.compactionEnabled,
                    triggerRatio = agent.compactTrigger / 100.0,
                    keepRecentTurns = agent.compactKeepTurns,
                    contextWindow = contextWindow,
                    offloadChars = agent.offloadChars,
                ),
            )
            // Each model call inside a run is charged on its own, so usage is
            // collected per turn and written to the call log there rather than
            // summed once at the end.
            val result = withContext(Dispatchers.IO) {
                run.client.run(
                    credential = credential,
                    history = run.transcript,
                    prompt = prompt,
                    modelId = modelId,
                    effort = effort,
                    contextWindow = contextWindow,
                    tools = tools,
                    approval = approval,
                    maxRounds = maxRounds,
                    sessionId = sessionId,
                    initialCompaction = run.compaction,
                    contextEngine = contextEngine,
                    promptImage = promptImage,
                    ask = { summary -> requestApproval(run, summary) },
                ) { event ->
                    when (event) {
                        is AgentClient.Event.Usage -> recordAgentCall(credential, event, modelId)
                        is AgentClient.Event.Failure -> recordAgentFailure(credential, event.message, modelId)
                        else -> Unit
                    }
                    // The client calls this from its own thread and cannot
                    // suspend, so the update is posted to the main thread and
                    // the worker moves on without waiting for it.
                    mainHandler.post { applyAgentEvent(run, event) }
                }
            }
            run.transcript = result.messages
            run.compaction = result.compaction
            // 会话可能已经被删除：那就不要再写回磁盘。
            val tracked = agentRuns.remove(run.id) != null
            // 释放可能还在等审批的线程。
            run.answers.offer(false)
            if (!tracked) return@launch
            val visible = agent.sessionId == run.id
            if (visible) {
                lastAgentTranscript = result.messages
                lastCompaction = result.compaction
                agent = agent.copy(running = false, pendingApproval = null)
            }
            persistSession(
                id = run.id,
                title = run.title,
                entries = run.entries,
                messages = result.messages,
                compaction = result.compaction,
                setActive = visible,
            )
        }
    }

    /**
     * 手动压缩当前会话：忽略触发阈值，至少把最老的一轮摘要掉。
     *
     * 与自动压缩共用 [ContextEngine]，因此原文归档、失败降级和协议配对规则完全一致。
     */
    private fun compactAgentNow() {
        if (agent.running) {
            Toast.makeText(this, "任务正在执行，结束后再压缩", Toast.LENGTH_SHORT).show()
            return
        }
        val credential = state.credential
        if (credential == null) {
            Toast.makeText(this, "请先登录", Toast.LENGTH_SHORT).show()
            return
        }
        val sessionId = agent.sessionId
        if (sessionId.isEmpty() || lastAgentTranscript.isEmpty()) {
            Toast.makeText(this, "当前会话还没有可压缩的历史", Toast.LENGTH_SHORT).show()
            return
        }
        if (agent.modelId.isBlank()) {
            Toast.makeText(this, "请先选择模型", Toast.LENGTH_SHORT).show()
            return
        }

        val modelId = agent.modelId
        agentScope.launch {
            val engine = ContextEngine(
                artifacts = ArtifactStore(this@MainActivity),
                config = ContextEngine.CompressionConfig(
                    enabled = true,
                    triggerRatio = 0.0,
                    keepRecentTurns = agent.compactKeepTurns,
                    contextWindow = agent.contextWindow,
                    offloadChars = agent.offloadChars,
                ),
            )
            val step = withContext(Dispatchers.IO) {
                engine.prepare(
                    messages = lastAgentTranscript,
                    sessionId = sessionId,
                    toolsTokens = 0,
                    state = lastCompaction,
                    force = true,
                ) { prompt ->
                    agentClient.summarize(credential, modelId, prompt) { event ->
                        if (event is AgentClient.Event.Usage) recordAgentCall(credential, event, modelId)
                    }
                }
            }

            lastAgentTranscript = step.messages
            lastCompaction = step.state

            val info = step.compaction
            if (info == null) {
                Toast.makeText(this@MainActivity, "最近 ${agent.compactKeepTurns} 轮会保留，没有可压缩的内容", Toast.LENGTH_SHORT).show()
            } else {
                agent = agent.copy(
                    entries = agent.entries + AgentEntry(
                        AgentEntry.Role.COMPACT,
                        buildString {
                            append("上下文已压缩：")
                            append(info.removed)
                            append(" 条消息 → 摘要 ")
                            append(info.summaryTokens)
                            append(" tokens")
                            if (info.fallback) append("（摘要生成失败，已硬截断）")
                            info.archivePath?.let { append("\n原文：$it") }
                        },
                    ),
                )
                Toast.makeText(this@MainActivity, "已压缩 ${info.removed} 条消息", Toast.LENGTH_SHORT).show()
            }
            persistAgentSession()
        }
    }

    /**
     * Writes one built-in chat turn to the call history.
     *
     * The endpoint's own calls are recorded by the service; this is the other
     * spender of the same account, so leaving it out would make the totals
     * disagree with the account's actual usage.
     */
    private fun recordAgentCall(credential: Credential, usage: AgentClient.Event.Usage, modelId: String) {
        callLog.append(
            CallRecord(
                timestamp = System.currentTimeMillis(),
                model = modelId,
                outcome = CallRecord.Outcome.OK,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                credits = usage.credits,
                accountId = credential.accountId,
                accountLabel = credential.nickname,
                multiplier = state.models.firstOrNull { it.id == modelId }?.multiplier ?: -1.0,
                source = CallRecord.Source.AGENT,
            ),
        )
    }

    /** Records a turn that failed before it produced usage. */
    private fun recordAgentFailure(credential: Credential, detail: String, modelId: String) {
        callLog.append(
            CallRecord(
                timestamp = System.currentTimeMillis(),
                model = modelId,
                outcome = CallRecord.Outcome.FAILED,
                detail = detail.take(120),
                accountId = credential.accountId,
                accountLabel = credential.nickname,
                multiplier = state.models.firstOrNull { it.id == modelId }?.multiplier ?: -1.0,
                source = CallRecord.Source.AGENT,
            ),
        )
    }

    /**
     * Shows the approval dialog and blocks until it is answered.
     *
     * Called from the worker thread running the tools, which is why it posts to
     * the main thread and then waits: the alternative is threading a callback
     * through the whole turn loop.
     */
    private fun requestApproval(run: AgentRun, summary: String): Boolean {
        // 丢掉上一轮停止时留下的答案，避免新的审批被直接拒绝。
        run.answers.clear()
        run.pendingApproval = summary
        mainHandler.post {
            // 后台会话的审批不抢屏幕：等用户切到该会话时再弹。
            if (agent.sessionId == run.id) agent = agent.copy(pendingApproval = summary)
        }
        // A bounded wait keeps a dismissed dialog from wedging the run forever;
        // timing out counts as a refusal, which is the safe direction.
        return runCatching { run.answers.poll(5, TimeUnit.MINUTES) }.getOrNull() ?: false
    }

    /** Records the user's answer and releases the waiting worker. */
    private fun answerApproval(allow: Boolean) {
        val run = agentRuns[agent.sessionId] ?: return
        run.pendingApproval = null
        agent = agent.copy(pendingApproval = null)
        run.answers.offer(allow)
    }

    /** 停止当前这一轮：断开在途请求，挂起的审批按拒绝处理。 */
    private fun stopAgent() {
        val run = agentRuns[agent.sessionId] ?: return
        run.client.cancel()
        run.pendingApproval = null
        agent = agent.copy(pendingApproval = null)
        // 释放可能正在等审批的线程。
        run.answers.offer(false)
    }

    /** Probes for root off the main thread, then enables the switch if granted. */
    private fun checkRootAvailability() {
        agentScope.launch {
            val available = withContext(Dispatchers.IO) { AgentTools.detectRoot() }
            agent = agent.copy(
                rootAvailable = available,
                // Losing root silently would leave the switch on while commands
                // quietly ran unprivileged.
                exposeRoot = agent.exposeRoot && available,
            )
        }
    }

    /**
     * Writes the conversation to disk.
     *
     * Stored as the model-facing transcript plus its visible rendering: the
     * first is what a follow-up needs, the second is what reopening should show,
     * and they cannot be derived from each other once tools are involved.
     */
    private fun persistAgentSession() {
        persistSession(
            id = agent.sessionId,
            title = agent.sessionTitle,
            entries = agent.entries,
            messages = lastAgentTranscript,
            compaction = lastCompaction,
            setActive = true,
        )
    }

    /**
     * 写入任意会话。
     *
     * 后台会话落盘时不改「当前会话」标记，否则重启后会打开最后结束的那个，
     * 而不是用户最后看到的那个。
     */
    private fun persistSession(
        id: String,
        title: String,
        entries: List<AgentEntry>,
        messages: List<AgentClient.Message>,
        compaction: CompactionState,
        setActive: Boolean,
    ) {
        if (id.isEmpty() || entries.isEmpty()) return
        val session = AgentStore.Session(
            id = id,
            title = title,
            updatedAt = System.currentTimeMillis(),
            messages = messages,
            entries = entries.mapNotNull { entry ->
                val role = when (entry.role) {
                    AgentEntry.Role.USER -> "USER"
                    AgentEntry.Role.ASSISTANT -> "ASSISTANT"
                    AgentEntry.Role.REASONING -> "REASONING"
                    AgentEntry.Role.TOOL -> "TOOL"
                    AgentEntry.Role.COMPACT -> "COMPACT"
                    AgentEntry.Role.ERROR -> "ERROR"
                }
                AgentStore.StoredEntry(role, entry.text)
            },
            compaction = compaction,
        )
        agentStore.save(session)
        if (setActive) agentStore.setActiveSessionId(id)
        agent = agent.copy(sessions = agentStore.sessions())
    }

    /** Opens a saved conversation, replacing whatever is on screen. */
    private fun openAgentSession(id: String) {
        if (id == agent.sessionId) return
        // 该会话还在跑：直接展示它自己的实时条目，并恢复运行状态。
        val live = agentRuns[id]
        if (live != null) {
            lastAgentTranscript = live.transcript
            lastCompaction = live.compaction
            agentStore.setActiveSessionId(id)
            agent = agent.copy(
                sessionId = id,
                sessionTitle = live.title,
                entries = live.entries,
                pendingImage = null,
                running = true,
                pendingApproval = live.pendingApproval,
            )
            return
        }
        val session = agentStore.load(id) ?: return
        lastAgentTranscript = session.messages
        lastCompaction = session.compaction
        agentStore.setActiveSessionId(id)
        agent = agent.copy(
            sessionId = session.id,
            sessionTitle = session.title,
            entries = session.entries.mapNotNull { entry ->
                val role = runCatching { AgentEntry.Role.valueOf(entry.role) }.getOrNull()
                    ?: return@mapNotNull null
                AgentEntry(role, entry.text)
            },
            pendingImage = null,
            running = false,
            pendingApproval = null,
        )
    }

    /** Starts a fresh conversation without deleting the saved one. */
    private fun newAgentSession() {
        lastAgentTranscript = emptyList()
        lastCompaction = CompactionState()
        agentStore.setActiveSessionId("")
        agent = agent.copy(
            entries = emptyList(),
            sessionId = "",
            sessionTitle = "",
            sessions = agentStore.sessions(),
            pendingImage = null,
            running = false,
            pendingApproval = null,
        )
    }

    private fun deleteAgentSession(id: String) {
        // 删掉正在跑的会话：先停掉，否则后台还会把结果写回磁盘。
        agentRuns.remove(id)?.let { run ->
            run.client.cancel()
            run.answers.offer(false)
        }
        agentStore.delete(id)
        if (agent.sessionId == id) {
            lastAgentTranscript = emptyList()
            lastCompaction = CompactionState()
            agent = agent.copy(
                entries = emptyList(),
                sessionId = "",
                sessionTitle = "",
                running = false,
                pendingApproval = null,
            )
        }
        agent = agent.copy(sessions = agentStore.sessions())
    }

    /** Folds one streamed event into the run's own transcript. */
    private fun applyAgentEvent(run: AgentRun, event: AgentClient.Event) {
        val entries = run.entries.toMutableList()
        var persist = false
        when (event) {
            is AgentClient.Event.Text -> appendStreaming(entries, AgentEntry.Role.ASSISTANT, event.delta)
            is AgentClient.Event.Reasoning -> appendStreaming(entries, AgentEntry.Role.REASONING, event.delta)
            is AgentClient.Event.ToolStart -> entries += AgentEntry(AgentEntry.Role.TOOL, "▶ ${event.name}")
            is AgentClient.Event.ToolEnd -> {
                entries += AgentEntry(AgentEntry.Role.TOOL, "◀ ${event.name}：${event.summary}")
                // Tool boundaries are the natural save points: they are coarse
                // enough not to write a file per token, and frequent enough that
                // a killed process keeps the work up to the last completed call.
                persist = true
            }
            is AgentClient.Event.Compacted -> {
                entries += AgentEntry(
                    AgentEntry.Role.COMPACT,
                    buildString {
                        append("上下文已压缩：")
                        append(event.removed)
                        append(" 条消息 → 摘要 ")
                        append(event.summaryTokens)
                        append(" tokens")
                        if (event.fallback) append("（摘要生成失败，已硬截断）")
                        event.archivePath?.let { append("\n原文：$it") }
                    },
                )
                persist = true
            }
            is AgentClient.Event.Failure -> {
                entries += AgentEntry(AgentEntry.Role.ERROR, event.message)
                persist = true
            }
            // Usage is consumed where the call log is written; the transcript has
            // nothing to show for it.
            is AgentClient.Event.Usage -> Unit
            AgentClient.Event.Done -> Unit
        }
        run.entries = entries
        // 只有这个会话正在屏幕上时才刷新 UI；后台的输出留在自己的条目里。
        if (agent.sessionId == run.id) agent = agent.copy(entries = entries)
        if (persist) {
            persistSession(
                id = run.id,
                title = run.title,
                entries = entries,
                messages = run.transcript,
                compaction = run.compaction,
                setActive = false,
            )
        }
    }

    /**
     * Appends a streamed fragment to the line it belongs to.
     *
     * Deltas arrive token by token, so consecutive fragments of the same role
     * extend the last entry rather than starting a new one.
     */
    private fun appendStreaming(entries: MutableList<AgentEntry>, role: AgentEntry.Role, delta: String) {
        val last = entries.lastOrNull()
        if (last != null && last.role == role) {
            entries[entries.lastIndex] = last.copy(text = last.text + delta)
        } else {
            entries += AgentEntry(role, delta)
        }
    }

    /** Rebuilds the conversation the next prompt should carry. */
    private fun agentHistory(): List<AgentClient.Message> = lastAgentTranscript

    private fun clearAgent() {
        // 退出登录等场景：所有在跑的会话都要停下来。
        agentRuns.values.forEach { run ->
            run.client.cancel()
            run.answers.offer(false)
        }
        agentRuns.clear()
        lastAgentTranscript = emptyList()
        lastCompaction = CompactionState()
        agent = agent.copy(
            entries = emptyList(),
            sessionId = "",
            sessionTitle = "",
            sessions = agentStore.sessions(),
            running = false,
            pendingApproval = null,
        )
    }

    private fun loadBalance() {
        val cred = state.credential
        if (cred == null) {
            state = state.copy(status = "余额：未登录")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                withLoading(Loading.BALANCE) { runCatching { upstream.fetchCredits(cred) } }
            }
            val balance = result.getOrNull()
            state = state.copy(
                balance = balance,
                status = if (balance == null) {
                    "余额查询失败：${result.exceptionOrNull()?.message?.take(80)}"
                } else {
                    ""
                },
            )
        }
    }

    /** Whether this package is exempt from battery optimisation. */
    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * Opens the system dialog that exempts the app from battery optimisation.
     * Without it aggressive OEM policies freeze the foreground service anyway.
     */
    private fun requestBatteryExemption() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.net.Uri.parse("package:$packageName")
        }
        runCatching { startActivity(intent) }.onFailure {
            // Some builds disable the direct request; the settings list always works.
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun copyEndpoint() {
        copyToClipboard("http://127.0.0.1:${state.port}/v1", "已复制接入地址")
    }

    private fun copyToClipboard(text: String, message: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("wb", text))
        toast(message)
    }

    /** Whether the status panel may be drawn over other apps. */
    private fun canDrawOverlay(): Boolean = android.provider.Settings.canDrawOverlays(this)

    /** Opens the system screen where the user grants overlay permission. */
    /**
     * Opens the all-files access page.
     *
     * Android 11 replaced the storage permissions with a per-app switch, so the
     * legacy runtime request no longer covers the agent's need to reach shared
     * storage; the user has to flip this one by hand.
     */
    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }.onFailure {
                // The per-app page is not on every build; the global list is.
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }.onFailure { toast("请在系统设置中授予文件访问权限") }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** Whether broad storage access is currently granted. */
    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private fun requestOverlayPermission() {
        runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure { toast("请在系统设置中允许悬浮窗") }
    }

    /** Pushes the burn-in settings to the live overlay. */
    private fun applyBurnIn() {
        BridgeService.overlayRef?.refreshBurnIn(
            enabled = BridgeSettings.burnInEnabled(this),
            intervalMs = BridgeSettings.burnInIntervalMs(this),
        )
    }

    /** Pushes the dot's appearance to the live overlay. */
    private fun applyDotAppearance() {
        val overlay = BridgeService.overlayRef ?: return
        overlay.refreshAppearance(
            color = BridgeSettings.dotColor(this),
            size = BridgeSettings.dotSize(this),
            shape = BridgeSettings.dotShape(this),
            alpha = BridgeSettings.dotAlpha(this),
        )
    }

    /** Reloads the recorded calls, newest last. */
    private fun loadCalls() {
        val limit = BridgeSettings.callLogLimit(this)
        state = state.copy(
            calls = callLog.load(),
            callsLoaded = true,
            accountTotals = callLog.totals(),
            callLogLimit = limit,
            callLogBytes = callLog.estimatedBytes(limit),
        )
    }

    // ------------------------------------------------------------------ //
    // 工程页
    // ------------------------------------------------------------------ //

    private fun openTooling() {
        state = state.copy(showTooling = true)
        refreshTooling()
    }

    private fun closeTooling() {
        state = state.copy(showTooling = false, tooling = state.tooling.copy(selected = null))
    }

    private fun selectToolingProject(id: String?) {
        if (id == null) {
            state = state.copy(tooling = state.tooling.copy(selected = null))
            return
        }
        state = state.copy(tooling = state.tooling.copy(loading = true))
        lifecycleScope.launch {
            val detail = withContext(Dispatchers.IO) { toolingDetail(id) }
            state = state.copy(
                tooling = state.tooling.copy(
                    selected = detail,
                    loading = false,
                    error = if (detail == null) "项目不存在" else "",
                ),
            )
        }
    }

    private fun refreshTooling() {
        val selectedId = state.tooling.selected?.project?.id
        state = state.copy(tooling = state.tooling.copy(loading = true, error = ""))
        lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val db = ToolingRuntime.get(this@MainActivity).db
                val projects = db.projects(50).map { it.toUi() }
                projects to selectedId?.let { toolingDetail(it) }
            }
            state = state.copy(
                tooling = state.tooling.copy(
                    projects = snapshot.first,
                    selected = snapshot.second,
                    loading = false,
                ),
            )
        }
    }

    /** Deletes a project together with its audit rows and workspace files. */
    private fun deleteToolingProject(id: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val runtime = ToolingRuntime.get(this@MainActivity)
                runtime.db.deleteProject(id)
                runtime.workspace.deleteProject(id)
            }
            if (state.tooling.selected?.project?.id == id) {
                state = state.copy(tooling = state.tooling.copy(selected = null))
            }
            refreshTooling()
            toast("项目已删除")
        }
    }

    private fun toolingDetail(id: String): ToolingProjectDetail? {
        val runtime = ToolingRuntime.get(this)
        val row = runtime.db.project(id) ?: return null
        return ToolingProjectDetail(
            project = row.toUi(),
            workspace = row.workspace,
            artifacts = runtime.db.artifacts(id).map {
                ToolingArtifact(it.kind, it.path, it.size, it.sha256, it.origin)
            },
            operations = runtime.db.operations(id).map {
                ToolingOperation(it.tool, it.status, it.summary, it.startedAt, it.outputRef)
            },
        )
    }

    private fun com.wbhub.app.tooling.ToolingDb.ProjectRow.toUi() = ToolingProject(
        id = id,
        name = name,
        packageName = packageName.orEmpty(),
        versionName = versionName.orEmpty(),
        status = status,
        updatedAt = updatedAt,
    )

    /**
     * Discards the visible history.
     *
     * The running totals survive: this empties a list, not the record of what
     * the endpoint has served.
     */
    private fun clearCalls() {
        callLog.clear()
        loadCalls()
        toast("调用记录已清空（累计统计保留）")
    }

    /** Signs the user out everywhere, discarding both stored credentials. */
    private fun logout() {
        Wire.Region.entries.forEach { store.clear(it) }
        refreshCredential()
        state = state.copy(models = emptyList(), balance = null, status = "已退出登录")
        toast("已退出登录")
    }

    private fun Wire.Region.toLoginRealm(): Login.Realm =
        if (this == Wire.Region.GLOBAL) Login.Realm.GLOBAL else Login.Realm.CN

    private fun regionLabel(region: Wire.Region): String =
        if (region == Wire.Region.GLOBAL) "国际版" else "国内版"

    private fun formatExpiry(cred: Credential): String {
        if (cred.expiresAt <= 0L) return ""
        // Credentials written by different tools carry either seconds or
        // milliseconds; anything far below the epoch-millisecond range is
        // seconds, so normalise before formatting.
        val millis = if (cred.expiresAt < 1_000_000_000_000L) cred.expiresAt * 1000 else cred.expiresAt
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Keeps a loading indicator visible long enough to be noticed. */
        private const val MIN_LOADING_MS = 600L
        private const val POLL_INTERVAL_MS = 3000L
        private const val LOGIN_TIMEOUT_MS = 9 * 60 * 1000L
    }
}
