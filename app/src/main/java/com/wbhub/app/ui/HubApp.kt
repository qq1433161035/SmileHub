package com.wbhub.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.bridge.DotShape
import com.wbhub.app.data.Login
import com.wbhub.app.proto.Wire

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HubApp(
    state: HubState,
    onStartBridge: (Int) -> Unit,
    onStopBridge: () -> Unit,
    onLogin: (Wire.Region) -> Unit,
    onLogout: () -> Unit,
    onClearCalls: () -> Unit,
    onOpenCallSettings: () -> Unit,
    onOpenModelStats: () -> Unit,
    onDismissModelStats: () -> Unit,
    onDismissCallSettings: () -> Unit,
    onCallLimitChange: (Int) -> Unit,
    onRefreshCalls: () -> Unit,
    onSwitchRealm: (Wire.Region) -> Unit,
    onSwitchAccount: (String) -> Unit,
    onDeleteAccount: (String) -> Unit,
    onOpenCredentialDetails: () -> Unit,
    onDismissCredentialDetails: () -> Unit,
    onCopyField: (String, String) -> Unit,
    onCheckin: () -> Unit,
    onCheckinAll: () -> Unit,
    onRefreshBalance: () -> Unit,
    onStreakBonus: () -> Unit,
    onActivityReport: () -> Unit,
    onTravel: () -> Unit,
    onNightOwl: () -> Unit,
    onAgentInput: (String) -> Unit,
    onAgentSend: () -> Unit,
    onAgentSelectModel: (String) -> Unit,
    onAgentSelectEffort: (String) -> Unit,
    onAgentSelectContext: (Int) -> Unit,
    onAgentToggleRoot: (Boolean) -> Unit,
    onAgentSelectApproval: (ApprovalMode) -> Unit,
    onAgentRequestStorage: () -> Unit,
    onAgentNewSession: () -> Unit,
    onAgentOpenSession: (String) -> Unit,
    onAgentDeleteSession: (String) -> Unit,
    onAgentAnswerApproval: (Boolean) -> Unit,
    agent: AgentState,
    onCopyEndpoint: () -> Unit,
    onCopyModel: (String) -> Unit,
    onRequestNotifications: () -> Unit,
    onRefreshModels: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onRequestOverlay: () -> Unit,
    onOverlayOpacity: (Float) -> Unit,
    onOverlayLocked: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onToggleLan: (Boolean) -> Unit,
    onSaveLanKey: (String) -> Unit,
    onOpenBridgeAdvanced: () -> Unit,
    onDismissBridgeAdvanced: () -> Unit,
    onToggleAggregate: (Boolean) -> Unit,
    onToggleAnthropic: (Boolean) -> Unit,
    onToggleResponses: (Boolean) -> Unit,
    onToggleHttps: (Boolean) -> Unit,
    onToggleDisguise: (Boolean) -> Unit,
    onDismissDisguisePrompt: () -> Unit,
    onToggleStealth: (Boolean) -> Unit,
    onDotColor: (Int) -> Unit,
    onDotSize: (Int) -> Unit,
    onDotShape: (DotShape) -> Unit,
    onDotAlpha: (Float) -> Unit,
    onBurnIn: (Boolean) -> Unit,
    onBurnInInterval: (Long) -> Unit,
    onAdjustDot: (Boolean) -> Unit,
    onDismissCheckin: () -> Unit,
    showHelp: Boolean,
    onShowHelp: () -> Unit,
    onDismissHelp: () -> Unit,
    onTabShown: (HubTab) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    // Reporting the visible tab lets the host reload that page's data on entry,
    // which is what keeps the call history current without a restart.
    LaunchedEffect(tab) { onTabShown(HubTab.entries[tab]) }
    var dark by remember { mutableStateOf(false) }

    MaterialExpressiveTheme(
        colorScheme = if (dark) darkColorScheme() else expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
    ) {
        val background = MaterialTheme.colorScheme.background
        Surface(color = background, contentColor = contentColorFor(background)) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text("WorkBuddy Hub", fontWeight = FontWeight.SemiBold)
                        },
                    )
                },
                bottomBar = {
                    NavigationBar {
                        HubTab.entries.forEachIndexed { index, item ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(item.icon, contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Pages slide in the direction the tabs were traversed, so
                // moving right and moving left look different. The curves come
                // from the expressive motion scheme rather than a plain tween,
                // which is what keeps the movement from feeling mechanical.
                AnimatedContent(
                    targetState = HubTab.entries[tab],
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val spec = MotionScheme.expressive()
                        val enter = slideInHorizontally(
                            animationSpec = spec.defaultSpatialSpec(),
                            initialOffsetX = { width -> if (forward) width / 5 else -width / 5 },
                        ) + fadeIn(animationSpec = spec.defaultEffectsSpec())
                        val exit = slideOutHorizontally(
                            animationSpec = spec.defaultSpatialSpec(),
                            targetOffsetX = { width -> if (forward) -width / 5 else width / 5 },
                        ) + fadeOut(animationSpec = spec.defaultEffectsSpec())
                        // The directions are swapped so the leaving page and the
                        // arriving one move as a pair rather than colliding.
                        (enter togetherWith exit).apply {
                            targetContentZIndex = if (forward) 1f else -1f
                        }
                    },
                    label = "tab",
                    modifier = Modifier.fillMaxSize(),
                ) { current ->
                    when (current) {
                        HubTab.Credential -> CredentialScreen(
                            state = state,
                            onSwitchRealm = onSwitchRealm,
                            onSwitchAccount = onSwitchAccount,
                            onDeleteAccount = onDeleteAccount,
                            onLogin = onLogin,
                            onLogout = onLogout,
                            onOpenDetails = onOpenCredentialDetails,
                        )
                        HubTab.Bridge -> BridgeScreen(
                            state = state,
                            onStart = onStartBridge,
                            onStop = onStopBridge,
                            onCopyEndpoint = onCopyEndpoint,
                            onShowHelp = onShowHelp,
                            onCopyModel = onCopyModel,
                            onRequestNotifications = onRequestNotifications,
                            onRefreshModels = onRefreshModels,
                            onRequestBatteryExemption = onRequestBatteryExemption,
                            onRequestOverlay = onRequestOverlay,
                            onOverlayOpacity = onOverlayOpacity,
                            onOverlayLocked = onOverlayLocked,
                            onSaveKey = onSaveKey,
                            onToggleLan = onToggleLan,
                            onCopyField = onCopyField,
                            onOpenAdvanced = onOpenBridgeAdvanced,
                            onSaveLanKey = onSaveLanKey,
                            onToggleStealth = onToggleStealth,
                            onDotColor = onDotColor,
                            onDotSize = onDotSize,
                            onDotShape = onDotShape,
                            onDotAlpha = onDotAlpha,
                            onBurnIn = onBurnIn,
                            onBurnInInterval = onBurnInInterval,
                            onAdjustDot = onAdjustDot,
                        )
                        HubTab.Agent -> AgentScreen(
                            agent = agent,
                            models = state.models,
                            onInputChange = onAgentInput,
                            onSend = onAgentSend,
                            onSelectModel = onAgentSelectModel,
                            onSelectEffort = onAgentSelectEffort,
                            onSelectContext = onAgentSelectContext,
                            onToggleRoot = onAgentToggleRoot,
                            onSelectApproval = onAgentSelectApproval,
                            onRequestStorage = onAgentRequestStorage,
                            onNewSession = onAgentNewSession,
                            onOpenSession = onAgentOpenSession,
                            onDeleteSession = onAgentDeleteSession,
                            onAnswerApproval = onAgentAnswerApproval,
                        )
                        HubTab.Calls -> CallsScreen(
                            state = state,
                            onClear = onClearCalls,
                            onRefresh = onRefreshCalls,
                            onOpenSettings = onOpenCallSettings,
                            onOpenModelStats = onOpenModelStats,
                        )
                        HubTab.Rewards -> RewardsScreen(
                            state = state,
                            onCheckin = onCheckin,
                            onCheckinAll = onCheckinAll,
                            onRefreshBalance = onRefreshBalance,
                            onStreakBonus = onStreakBonus,
                            onActivityReport = onActivityReport,
                            onTravel = onTravel,
                            onNightOwl = onNightOwl,
                        )
                    }
                }
                }
            }
        }

        if (state.checkinMessage.isNotBlank()) {
            CheckinDialog(
                message = state.checkinMessage,
                items = state.checkinItems,
                onDismiss = onDismissCheckin,
            )
        }
        if (state.showCredentialDrawer) {
            CredentialDetailDrawer(
                state = state,
                onDismiss = onDismissCredentialDetails,
                onCopy = onCopyField,
            )
        }
        if (state.showModelStats) {
            ModelStatsSheet(state = state, onDismiss = onDismissModelStats)
        }
        if (state.showBridgeAdvanced) {
            BridgeAdvancedSheet(
                state = state,
                onDismiss = onDismissBridgeAdvanced,
                onToggleAggregate = onToggleAggregate,
                onToggleAnthropic = onToggleAnthropic,
                onToggleResponses = onToggleResponses,
                onToggleHttps = onToggleHttps,
                onToggleDisguise = onToggleDisguise,
            )
        }
        if (state.showDisguisePrompt) {
            DisguisePromptDialog(
                onConfirm = { onToggleDisguise(true) },
                onDismiss = onDismissDisguisePrompt,
            )
        }
        if (state.showCallSettings) {
            CallSettingsSheet(
                state = state,
                onDismiss = onDismissCallSettings,
                onLimitChange = onCallLimitChange,
            )
        }
        if (showHelp) {
            HelpDialog(
                state = state,
                onDismiss = onDismissHelp,
                onCopyEndpoint = onCopyEndpoint,
            )
        }
    }
}
