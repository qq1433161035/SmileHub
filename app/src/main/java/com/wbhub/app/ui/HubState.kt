package com.wbhub.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.ui.graphics.vector.ImageVector
import com.wbhub.app.agent.AgentStore
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.agent.SubagentLimits
import com.wbhub.app.agent.SystemPrompts
import com.wbhub.app.bridge.CallRecord
import com.wbhub.app.bridge.AccountTotals
import com.wbhub.app.bridge.DotShape
import com.wbhub.app.data.CheckinItem
import com.wbhub.app.proto.Balance
import com.wbhub.app.proto.Credential
import com.wbhub.app.proto.SavedAccount
import com.wbhub.app.proto.HubModel
import com.wbhub.app.proto.Wire

enum class HubTab(val label: String, val icon: ImageVector) {
    Personal("个人信息", Icons.Default.Person),
    Bridge("API", Icons.Default.Hub),
    Agent("对话", Icons.Default.SmartToy),
    Settings("设置", Icons.Default.Settings),
}

/** Everything the screens render; owned by the activity and backed by the service. */
data class HubState(
    val credential: Credential? = null,
    val expiryText: String = "",
    val bridgeRunning: Boolean = false,
    val port: Int = 8765,
    val secret: String = "wb-local",
    /** Whether the endpoint also answers on the local network. */
    val lanEnabled: Boolean = false,
    /** Whether a client asking for a single response is served one. */
    val aggregateNonStream: Boolean = false,
    /** Whether the Anthropic Messages dialect is served. */
    val formatAnthropic: Boolean = false,
    /** Whether the OpenAI Responses dialect is served. */
    val formatResponses: Boolean = false,
    /** Whether the endpoint also answers over TLS. */
    val httpsEnabled: Boolean = false,
    /** Whether a client's self-identifying system prompt is rewritten. */
    val disguiseIdentity: Boolean = false,
    /** Whether the prompt offering the disguise is open. */
    val showDisguisePrompt: Boolean = false,
    /** Whether the API platform's advanced settings sheet is open. */
    val showBridgeAdvanced: Boolean = false,
    /** Key peers on the network must present; separate from the local one. */
    val lanKey: String = "wb-lan",
    /** Addresses a peer on the same network can use, empty when none. */
    val lanAddresses: List<String> = emptyList(),
    val status: String = "",
    val balance: Balance? = null,
    val checkinMessage: String = "",
    val checkinItems: List<CheckinItem> = emptyList(),
    val models: List<HubModel> = emptyList(),
    val notificationsAllowed: Boolean = true,
    val batteryExempt: Boolean = false,
    val loading: Loading? = null,
    val showCredentialDrawer: Boolean = false,
    val showLogoutConfirm: Boolean = false,
    val overlayAllowed: Boolean = false,
    /** Whether the running bridge shows its floating panel. */
    val overlayEnabled: Boolean = false,
    val calls: List<CallRecord> = emptyList(),
    /** Whether the call log has been read; the page loads it on demand. */
    val callsLoaded: Boolean = false,
    /** Running totals per account, independent of the list cap. */
    val accountTotals: AccountTotals = AccountTotals(),
    /** How many call records to keep. */
    val callLogLimit: Int = 500,
    /** Whether the call-record settings sheet is open. */
    val showCallSettings: Boolean = false,
    /** Whether the model-frequency sheet is open. */
    val showModelStats: Boolean = false,
    /** Approximate size the history occupies at the current limit. */
    val callLogBytes: Long = 0,
    val overlayOpacity: Float = 0.94f,
    val overlayLocked: Boolean = false,
    /** Whether the overlay is a non-interactive dot instead of the panel. */
    val stealthEnabled: Boolean = false,
    val dotColor: Int = 0xFF34C759.toInt(),
    val dotSize: Int = 12,
    val dotShape: DotShape = DotShape.FILLED,
    val dotAlpha: Float = 1f,
    /** Whether the dot cycles positions to spread the lit pixels. */
    val burnInEnabled: Boolean = true,
    /** How long the dot stays in one position, in milliseconds. */
    val burnInIntervalMs: Long = 120_000L,
    /** Whether the dot is temporarily movable. */
    val adjustingDot: Boolean = false,
    val realm: Wire.Region = Wire.Region.CN,
    val accounts: Map<Wire.Region, List<SavedAccount>> = emptyMap(),
    val activeAccountId: String? = null,
    /** Whether the 工程 page covers the tab UI. */
    val showTooling: Boolean = false,
    /** Projects and their artifacts, loaded when the 工程 page opens. */
    val tooling: ToolingState = ToolingState(),
)

/** One line in the agent transcript. */
data class AgentEntry(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT, REASONING, TOOL, COMPACT, ERROR }
}

/** Everything the agent page renders. */
data class AgentState(
    val entries: List<AgentEntry> = emptyList(),
    val input: String = "",
    /** Data URL of the image attached to the next prompt, or null. */
    val pendingImage: String? = null,
    val modelId: String = "",
    val effort: String = "",
    val contextWindow: Int = 0,
    val running: Boolean = false,
    /** Whether tool calls may run as root. Only offered once root is confirmed. */
    val exposeRoot: Boolean = false,
    /** Whether this device actually granted root, probed on demand. */
    val rootAvailable: Boolean = false,
    val approval: ApprovalMode = ApprovalMode.AUTO,
    /** Tool round cap for one prompt; user-configurable. */
    val maxRounds: Int = 100,
    /** 上下文压缩总开关。 */
    val compactionEnabled: Boolean = true,
    /** 触发压缩的窗口占比（30–95）。 */
    val compactTrigger: Int = 70,
    /** 压缩时保留最近多少个用户轮次。 */
    val compactKeepTurns: Int = 6,
    /** 工具输出超过多少字符落盘。 */
    val offloadChars: Int = 32_000,
    /** 用户自定义全局指令；内置基线始终注入。 */
    val systemPrompt: String = "",
    /** 是否注入用户全局指令。 */
    val systemPromptEnabled: Boolean = true,
    /** 当前生效的内置基线；可被用户覆盖。 */
    val systemBaseline: String = SystemPrompts.BASELINE,
    /** 同时运行的子代理数量，1–4。 */
    val subagentConcurrency: Int = SubagentLimits.DEFAULT_CONCURRENT,
    /** 长期记忆总开关。 */
    val memoryEnabled: Boolean = false,
    /** 每次提问最多注入多少条记忆。 */
    val memoryTopK: Int = 5,
    /** 召回最低分，0–100。 */
    val memoryMinScore: Int = 30,
    /** 记忆条目数与占用空间，用于设置页展示。 */
    val memoryCount: Int = 0,
    val memoryBytes: Long = 0,
    val storageGranted: Boolean = false,
    val workDir: String = AgentTools.defaultWorkDir().absolutePath,
    /** Title of the conversation in progress, shown next to the session picker. */
    val sessionTitle: String = "",
    /** Pending approval request, or null when nothing is waiting. */
    val pendingApproval: String? = null,
    /** Id of the conversation in progress; empty means nothing is saved yet. */
    val sessionId: String = "",
    /** Saved conversations, newest first, for the picker. */
    val sessions: List<AgentStore.Session> = emptyList(),
)

/** Which operation is in flight, so each control can show its own indicator. */
enum class Loading { CHECKIN, BALANCE, MODELS, STREAK, ACTIVITY, TRAVEL, NIGHT }

/** Whether any build holds at least one saved account. */
val HubState.hasAnyCredential: Boolean
    get() = accounts.values.any { it.isNotEmpty() }
