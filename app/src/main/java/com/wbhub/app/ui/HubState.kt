package com.wbhub.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Key
import androidx.compose.ui.graphics.vector.ImageVector
import com.wbhub.app.agent.AgentStore
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
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
    Credential("凭证", Icons.Default.Key),
    Bridge("API 平台", Icons.Default.Hub),
    Agent("对话", Icons.Default.SmartToy),
    Calls("调用记录", Icons.Default.ReceiptLong),
    Rewards("积分", Icons.Default.CardGiftcard),
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
    val calls: List<CallRecord> = emptyList(),
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
)

/** One line in the agent transcript. */
data class AgentEntry(
    val role: Role,
    val text: String,
) {
    enum class Role { USER, ASSISTANT, REASONING, TOOL, ERROR }
}

/** Everything the agent page renders. */
data class AgentState(
    val entries: List<AgentEntry> = emptyList(),
    val input: String = "",
    val modelId: String = "",
    val effort: String = "",
    val contextWindow: Int = 0,
    val running: Boolean = false,
    /** Whether tool calls may run as root. Only offered once root is confirmed. */
    val exposeRoot: Boolean = false,
    /** Whether this device actually granted root, probed on demand. */
    val rootAvailable: Boolean = false,
    val approval: ApprovalMode = ApprovalMode.AUTO,
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
