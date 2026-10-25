package com.wbhub.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.wbhub.app.access.AccessibilityBridge
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.agent.SystemPrompts
import com.wbhub.app.agent.memory.MemoryEvent
import com.wbhub.app.agent.memory.MemoryRecord
import com.wbhub.app.agent.memory.MemoryStore
import com.wbhub.app.agent.memory.MemoryType
import com.wbhub.app.agent.memory.updateMemory
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.adb.AdbPairing
import com.wbhub.app.tools.worker.ToolWorkerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页：只放全局开关和默认值，不再挤在输入框上方。
 *
 * 模型与推理强度仍留在对话页，因为它们是一次请求的选择；这里的项目会跨会话、
 * 跨任务生效。
 */
@Composable
fun SettingsScreen(
    agent: AgentState,
    onSelectApproval: (ApprovalMode) -> Unit,
    onSelectMaxRounds: (Int) -> Unit,
    onToggleCompaction: (Boolean) -> Unit,
    onSelectCompactTrigger: (Int) -> Unit,
    onSelectCompactKeep: (Int) -> Unit,
    onSelectOffloadChars: (Int) -> Unit,
    onToggleSystemPrompt: (Boolean) -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onSaveSystemBaseline: (String) -> Unit,
    onSelectSubagentConcurrency: (Int) -> Unit,
    onCompactNow: () -> Unit,
    onToggleMemory: (Boolean) -> Unit,
    onSelectMemoryTopK: (Int) -> Unit,
    onSelectMemoryMinScore: (Int) -> Unit,
    onRebuildMemory: () -> Unit,
    onClearMemory: () -> Unit,
    onMemoryChanged: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onRequestStorage: () -> Unit,
) {
    var confirmAllowAll by remember { mutableStateOf(false) }
    var editingRounds by remember { mutableStateOf(false) }
    var roundsInput by remember { mutableStateOf(agent.maxRounds.toString()) }
    var confirmAutoUi by remember { mutableStateOf(false) }
    var confirmAllowEnv by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppStorage.prefs(context, "adb") }
    val accessPrefs = remember { AppStorage.prefs(context, "accessibility") }
    val toolPrefs = remember { AppStorage.prefs(context, "tooling") }
    var allowEnv by remember { mutableStateOf(toolPrefs.getBoolean("allow_env_change", false)) }
    val accessibility by AccessibilityBridge.state.collectAsState()
    var autoUi by remember { mutableStateOf(accessPrefs.getBoolean("auto_approve", false)) }
    var rootAvailable by remember { mutableStateOf(agent.rootAvailable) }
    var rootMessage by remember { mutableStateOf("") }
    var adbAuto by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var adbHint by remember { mutableStateOf("") }
    var showMemories by remember { mutableStateOf(false) }
    var memories by remember { mutableStateOf<List<MemoryRecord>>(emptyList()) }
    var memoryEvents by remember { mutableStateOf<Map<Long, List<MemoryEvent>>>(emptyMap()) }
    var memoryLoading by remember { mutableStateOf(false) }
    var memoryFilter by remember { mutableStateOf<MemoryType?>(null) }
    var memoryEditing by remember { mutableStateOf<MemoryRecord?>(null) }
    var memoryEditText by remember { mutableStateOf("") }
    var memoryEditMessage by remember { mutableStateOf("") }
    var memoryDeleting by remember { mutableStateOf<MemoryRecord?>(null) }
    var confirmClearMemory by remember { mutableStateOf(false) }
    var editingSystemPrompt by remember { mutableStateOf(false) }
    var systemPromptDraft by remember { mutableStateOf("") }
    var showSystemPreview by remember { mutableStateOf(false) }
    var editingBaseline by remember { mutableStateOf(false) }
    var baselineDraft by remember { mutableStateOf("") }

    // 库在 IO 上打开；组合期不碰数据库。列表最多 200 条。
    suspend fun loadMemories(filter: MemoryType?) {
        val pair = withContext(Dispatchers.IO) {
            val store = MemoryStore.get(context)
            val rows = store.list(type = filter, limit = 200)
            rows to rows.associate { it.id to store.events(it.id, 3) }
        }
        memories = pair.first
        memoryEvents = pair.second
    }

    LaunchedEffect(showMemories, memoryFilter) {
        if (showMemories) {
            memoryLoading = true
            loadMemories(memoryFilter)
            memoryLoading = false
        }
    }
    // ADB 会话在 :tools 进程里，这里按秒读取它的状态。
    var adbState by remember {
        mutableStateOf(ToolWorkerClient.AdbUiState(false, null, "未连接"))
    }
    // 系统设置可能在应用之外被改过：每次回到前台重新读一次真实状态。
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner

    LaunchedEffect(Unit) {
        while (true) {
            // 只在前台轮询：设置页没在前台时没必要跨进程拉起 :tools。
            val resumed = lifecycleOwner?.lifecycle?.currentState
                ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) != false
            if (resumed) {
                adbState = withContext(Dispatchers.IO) { ToolWorkerClient.get(context).adbState() }
            }
            delay(3_000)
        }
    }

    LaunchedEffect(agent.rootAvailable) { rootAvailable = agent.rootAvailable }
    DisposableEffect(lifecycleOwner) {
        val owner = lifecycleOwner ?: return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) AccessibilityBridge.refresh(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { AccessibilityBridge.refresh(context) }

    fun openAccessibilitySettings() {
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (!opened) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionLabel("工具执行") }

        item {
            SettingRow(
                title = "全部工具不再弹窗",
                subtitle = if (agent.approval == ApprovalMode.ALLOW_ALL) {
                    "所有工具调用直接执行"
                } else {
                    "读取放行，写入、命令、签名会先询问"
                },
            ) {
                Switch(
                    checked = agent.approval == ApprovalMode.ALLOW_ALL,
                    onCheckedChange = { checked ->
                        if (checked) {
                            confirmAllowAll = true
                        } else {
                            onSelectApproval(ApprovalMode.AUTO)
                        }
                    },
                )
            }
        }

        item {
            SettingRow(
                title = "允许改动运行环境",
                subtitle = if (allowEnv) {
                    "下载、解包、编译、改写工具目录时不再询问"
                } else {
                    "下载、解包、编译、改写工具目录时会先询问"
                },
            ) {
                Switch(
                    checked = allowEnv,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            // 放行环境变更是危险方向，先确认一次，避免误触。
                            confirmAllowEnv = true
                        } else {
                            allowEnv = false
                            toolPrefs.edit().putBoolean("allow_env_change", false).apply()
                        }
                    },
                )
            }
        }

        item { SectionLabel("设备与权限") }

        item {
            SettingRow(
                title = "root 权限",
                subtitle = when {
                    agent.exposeRoot && rootAvailable -> "已授权，命令可以走 su"
                    rootMessage.isNotEmpty() -> rootMessage
                    else -> "打开开关会请求 su 授权；没有 root 时自动改用 ADB shell"
                },
            ) {
                Switch(
                    checked = agent.exposeRoot && rootAvailable,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            rootAvailable = false
                            rootMessage = "已关闭 root 执行"
                            onToggleRoot(false)
                        } else {
                            rootMessage = "正在请求 root，请在弹窗里允许…"
                            scope.launch {
                                val granted = withContext(Dispatchers.IO) {
                                    AgentTools.detectRoot(30)
                                }
                                rootAvailable = granted
                                onToggleRoot(granted)
                                rootMessage = if (granted) {
                                    "已授权 root"
                                } else {
                                    "未授权：su 被拒绝或不可用"
                                }
                            }
                        }
                    },
                )
            }
        }

        item {
            SettingRow(
                title = "所有文件访问",
                subtitle = if (agent.storageGranted) {
                    "已授权，可读写 /storage/emulated/0"
                } else {
                    "未授权，无法读写共享存储中的文件"
                },
            ) {
                if (agent.storageGranted) {
                    Text(
                        "已开启",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    TextButton(onClick = onRequestStorage) { Text("去授权") }
                }
            }
        }

        item { SectionLabel("无线调试（ADB）") }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AssistChip(
                        onClick = {
                            AdbPairing.openWirelessDebugging(context)
                            adbHint = "已跳到系统无线调试页：打开开关后点「使用配对码配对设备」"
                        },
                        label = { Text("打开无线调试") },
                    )
                    AssistChip(
                        onClick = {
                            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                                adbHint = "通知被系统关闭了：到「设置 → 通知」里允许本应用发通知，再点一次"
                            } else if (AdbPairing.show(context)) {
                                adbHint = "通知已发出：保持系统配对弹窗别关，下拉通知栏填配对码"
                            } else {
                                adbHint = "通知发送失败，检查通知权限"
                            }
                        },
                        label = { Text("输入配对码") },
                    )
                    AssistChip(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { ToolWorkerClient.get(context).adbResetKeys() }
                                adbHint = "已删除本机密钥，需要在无线调试里重新配对"
                            }
                        },
                        label = { Text("重置密钥") },
                    )
                }
                Text(
                    if (adbState.connected) "已连接 ${adbState.endpoint}" else adbState.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (adbState.connected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (adbHint.isNotEmpty()) {
                    Text(
                        adbHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SettingRow(
                    title = "启动时自动连接",
                    subtitle = "配对成功后建议开启；无线调试关闭时连接会失败",
                ) {
                    Switch(
                        checked = adbAuto,
                        onCheckedChange = { enabled ->
                            adbAuto = enabled
                            prefs.edit().putBoolean("auto", enabled).apply()
                        },
                    )
                }
            }
        }

        item { SectionLabel("无障碍") }

        item {
            SettingRow(
                title = "无障碍权限",
                subtitle = when {
                    accessibility.connected -> accessibility.message
                    accessibility.enabled -> "已授权，等待系统连接"
                    else -> "未开启，点击前往系统设置"
                },
            ) {
                Switch(
                    checked = accessibility.enabled,
                    onCheckedChange = { openAccessibilitySettings() },
                )
            }
        }

        item {
            SettingRow(
                title = "不再弹窗",
                subtitle = when {
                    !accessibility.enabled -> "先开启无障碍权限"
                    autoUi -> "点击、输入、滚动不再逐次询问，审计记 AUTO_UI"
                    else -> "每次点击与输入仍会先询问"
                },
            ) {
                Switch(
                    checked = autoUi && accessibility.enabled,
                    enabled = accessibility.enabled,
                    onCheckedChange = { enabled ->
                        if (enabled) {
                            confirmAutoUi = true
                        } else {
                            autoUi = false
                            accessPrefs.edit().putBoolean("auto_approve", false).apply()
                        }
                    },
                )
            }
        }

        item { SectionLabel("全局指令") }

        item {
            SettingRow(
                title = "注入自定义指令",
                subtitle = when {
                    !agent.systemPromptEnabled -> "已关闭：每次请求只带内置基线"
                    agent.systemPrompt.isBlank() -> "未填写：每次请求只带内置基线"
                    else -> "已启用：${agent.systemPrompt.length} 字随每次请求注入"
                },
            ) {
                Switch(checked = agent.systemPromptEnabled, onCheckedChange = onToggleSystemPrompt)
            }
        }

        item {
            SettingRow(
                title = "编辑全局指令",
                subtitle = "跨会话生效，不写入聊天记录；硬性拦截仍由工具审批与路径边界负责",
            ) {
                TextButton(
                    onClick = {
                        systemPromptDraft = agent.systemPrompt
                        showSystemPreview = false
                        editingSystemPrompt = true
                    },
                ) { Text("编辑") }
            }
        }

        item {
            SettingRow(
                title = "内置提示词",
                subtitle = if (agent.systemBaseline != SystemPrompts.BASELINE) {
                    "已修改：${agent.systemBaseline.length} 字，排在全局指令之前"
                } else {
                    "默认基线：${agent.systemBaseline.length} 字，可编辑或恢复默认"
                },
            ) {
                TextButton(
                    onClick = {
                        baselineDraft = agent.systemBaseline
                        editingBaseline = true
                    },
                ) { Text("编辑") }
            }
        }

        item {
            Text(
                "提示词只保存在应用私有目录，不写聊天记录；修改后下一次提问生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionLabel("子代理") }

        item {
            OptionRow(
                title = "并发数",
                current = "${agent.subagentConcurrency} 个",
                options = listOf(1 to "1", 2 to "2", 3 to "3", 4 to "4"),
                onSelect = onSelectSubagentConcurrency,
            )
        }

        item {
            Text(
                "同时运行的子代理数量；单次最多拆 4 个任务，超出部分排队。子代理只读，修改仍由主代理执行；改动对下一次子代理调用生效。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionLabel("上下文压缩") }

        item {
            SettingRow(
                title = "自动压缩历史",
                subtitle = if (agent.compactionEnabled) {
                    "超过 ${agent.compactTrigger}% 窗口时摘要较早轮次，原文归档可查"
                } else {
                    "关闭后历史全量发送，长任务可能超出上下文"
                },
            ) {
                Switch(checked = agent.compactionEnabled, onCheckedChange = onToggleCompaction)
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "轮次上限",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${agent.maxRounds} 轮",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(20, 50, 100, 200, 500).forEach { value ->
                        AssistChip(
                            onClick = { onSelectMaxRounds(value) },
                            label = { Text("$value") },
                        )
                    }
                    AssistChip(
                        onClick = {
                            roundsInput = agent.maxRounds.toString()
                            editingRounds = true
                        },
                        label = { Text("自定义") },
                    )
                }
                Text(
                    "一次任务中模型与工具最多往返的轮数，超过后自动停止。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (agent.compactionEnabled) {
            item {
                OptionRow(
                    title = "触发阈值",
                    current = "${agent.compactTrigger}%",
                    options = listOf(
                        50 to "50%",
                        60 to "60%",
                        70 to "70%",
                        80 to "80%",
                        90 to "90%",
                    ),
                    onSelect = onSelectCompactTrigger,
                )
            }
            item {
                OptionRow(
                    title = "保留最近轮次",
                    current = "${agent.compactKeepTurns} 轮",
                    options = listOf(
                        2 to "2",
                        4 to "4",
                        6 to "6",
                        10 to "10",
                        20 to "20",
                    ),
                    onSelect = onSelectCompactKeep,
                )
            }
            item {
                OptionRow(
                    title = "工具输出落盘阈值",
                    current = "${agent.offloadChars / 1000} KB",
                    options = listOf(
                        8_000 to "8 KB",
                        16_000 to "16 KB",
                        32_000 to "32 KB",
                        64_000 to "64 KB",
                        128_000 to "128 KB",
                    ),
                    onSelect = onSelectOffloadChars,
                )
            }
            item {
                SettingRow(
                    title = "立即压缩当前会话",
                    subtitle = "忽略阈值，至少摘要最老的一轮；最近轮次保留原文",
                ) {
                    TextButton(onClick = onCompactNow) { Text("压缩") }
                }
            }
            item {
                Text(
                    "压缩只处理较早的完整轮次，最近轮次与工具定义保持原文；被移出的内容保存在配置目录的 agent/sessions/<会话>/ 下。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionLabel("长期记忆") }

        item {
            SettingRow(
                title = "长期记忆",
                subtitle = if (agent.memoryEnabled) {
                    "跨会话召回已确认的事实、路径与偏好"
                } else {
                    "关闭时不建索引、不注入，零额外开销"
                },
            ) {
                Switch(checked = agent.memoryEnabled, onCheckedChange = onToggleMemory)
            }
        }

        if (agent.memoryEnabled) {
            item {
                OptionRow(
                    title = "每次召回条数",
                    current = "${agent.memoryTopK} 条",
                    options = listOf(3 to "3", 5 to "5", 8 to "8", 10 to "10"),
                    onSelect = onSelectMemoryTopK,
                )
            }
            item {
                OptionRow(
                    title = "最低相关度",
                    current = "${agent.memoryMinScore}%",
                    options = listOf(
                        20 to "20%",
                        25 to "25%",
                        30 to "30%",
                        40 to "40%",
                        50 to "50%",
                    ),
                    onSelect = onSelectMemoryMinScore,
                )
            }
            item {
                SettingRow(
                    title = "从最近会话建立索引",
                    subtitle = "读取最近 20 个会话的压缩摘要，按时间顺序处理",
                ) {
                    TextButton(onClick = onRebuildMemory) { Text("建立") }
                }
            }
        }

        // 查看/清空不跟随开关隐藏：关闭后仍要能治理已有数据。
        item {
            SettingRow(
                title = "编辑记忆库",
                subtitle = if (agent.memoryEnabled) {
                    "已保存 ${agent.memoryCount} 条 · ${formatBytes(agent.memoryBytes)}，可逐条修改或删除"
                } else {
                    "已关闭；仍可打开查看、编辑或清空已有记忆"
                },
            ) {
                TextButton(onClick = { showMemories = true }) { Text("编辑") }
            }
        }
        item {
            SettingRow(
                title = "清空长期记忆",
                subtitle = "不会删除会话原文与归档",
            ) {
                TextButton(onClick = { confirmClearMemory = true }) { Text("清空") }
            }
        }
        item {
            Text(
                "记忆仅存放在应用私有目录，不上传；命中密钥、令牌或凭据的内容会被整条丢弃。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionLabel("工作目录") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        agent.workDir,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "任务产物只写入此目录，可自行建子目录；配置目录不存放任务文件。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item { SectionLabel("配置目录") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        AppStorage.root(LocalContext.current).absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        "仅存放设置、会话、数据库与工具，不存放任务产物。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }

    if (confirmAllowAll) {
        AlertDialog(
            onDismissRequest = { confirmAllowAll = false },
            title = { Text("允许所有工具直接执行？") },
            text = {
                SelectionContainer {
                    Text("开启后写文件、执行 shell、解码/重打包、签名都不会再弹窗。仅在明确知道当前任务要做什么时开启。")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAllowAll = false
                        onSelectApproval(ApprovalMode.ALLOW_ALL)
                    },
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAllowAll = false }) { Text("取消") }
            },
        )
    }

    if (confirmAutoUi) {
        AlertDialog(
            onDismissRequest = { confirmAutoUi = false },
            title = { Text("让 AI 直接操作界面？") },
            text = {
                Text(
                    "AI 将可以直接点击、输入、滚动其他应用，不再逐次询问。\n\n" +
                        "仅对界面调试工具有效：写文件、执行命令、安装应用仍会先询问；" +
                        "系统设置、安装器与支付类界面仍会被拦截。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAutoUi = false
                        autoUi = true
                        accessPrefs.edit().putBoolean("auto_approve", true).apply()
                    },
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAutoUi = false }) { Text("取消") }
            },
        )
    }

    if (confirmAllowEnv) {
        AlertDialog(
            onDismissRequest = { confirmAllowEnv = false },
            title = { Text("允许改动运行环境？") },
            text = {
                SelectionContainer {
                    Text(
                        "开启后，模型可以不经询问就下载、解包、编译可执行文件，" +
                            "或改写 tools/、py_modules/、/data/local/tmp 等目录，" +
                            "任务可能损坏运行环境。",
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmAllowEnv = false
                        allowEnv = true
                        toolPrefs.edit().putBoolean("allow_env_change", true).apply()
                    },
                ) { Text("允许") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAllowEnv = false }) { Text("取消") }
            },
        )
    }

    if (editingRounds) {
        AlertDialog(
            onDismissRequest = { editingRounds = false },
            title = { Text("工具轮次上限") },
            text = {
                OutlinedTextField(
                    value = roundsInput,
                    onValueChange = { roundsInput = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("1 - 1000") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSelectMaxRounds(roundsInput.toIntOrNull()?.coerceIn(1, 1000) ?: agent.maxRounds)
                        editingRounds = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { editingRounds = false }) { Text("取消") }
            },
        )
    }

    if (editingSystemPrompt) {
        AlertDialog(
            onDismissRequest = { editingSystemPrompt = false },
            title = { Text("全局指令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "每次请求都会注入，包括新会话。内置基线始终生效，这里只写你自己的约束。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SystemPrompts.TEMPLATES.forEach { (name, template) ->
                            AssistChip(
                                onClick = {
                                    // 追加而不是覆盖：多条约束可以同时存在。
                                    systemPromptDraft = (
                                        if (systemPromptDraft.isBlank()) template
                                        else systemPromptDraft.trimEnd() + "\n" + template
                                        ).take(SystemPrompts.MAX_USER_CHARS)
                                },
                                label = { Text(name) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = systemPromptDraft,
                        onValueChange = { systemPromptDraft = it.take(SystemPrompts.MAX_USER_CHARS) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp, max = 220.dp),
                        label = { Text("指令内容") },
                        placeholder = { Text("例如：改动前先备份到 workspace/backup；回答先给结论。") },
                    )
                    Text(
                        "${systemPromptDraft.length} / ${SystemPrompts.MAX_USER_CHARS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (systemPromptDraft.length >= SystemPrompts.MAX_USER_CHARS) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    TextButton(onClick = { showSystemPreview = !showSystemPreview }) {
                        Text(if (showSystemPreview) "收起预览" else "预览系统提示（不含记忆）")
                    }
                    if (showSystemPreview) {
                        Text(
                            "以下为内置基线 + 全局指令；实际请求还会追加长期记忆，国际版另有默认提示。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SelectionContainer {
                            Text(
                                SystemPrompts.compose(
                                    systemPromptDraft,
                                    null,
                                    baseline = agent.systemBaseline,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveSystemPrompt(systemPromptDraft)
                        editingSystemPrompt = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { systemPromptDraft = "" }) { Text("清空") }
                    TextButton(onClick = { editingSystemPrompt = false }) { Text("取消") }
                }
            },
        )
    }

    if (editingBaseline) {
        AlertDialog(
            onDismissRequest = { editingBaseline = false },
            title = { Text("内置提示词") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "排在全局指令之前，每次请求都会带上；保存为空或点“恢复默认”即回到内置文本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = baselineDraft,
                        onValueChange = { baselineDraft = it.take(SystemPrompts.MAX_BASELINE_CHARS) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 240.dp),
                        label = { Text("基线内容") },
                    )
                    Text(
                        "${baselineDraft.length} / ${SystemPrompts.MAX_BASELINE_CHARS}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (baselineDraft.length >= SystemPrompts.MAX_BASELINE_CHARS) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveSystemBaseline(baselineDraft)
                        editingBaseline = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { baselineDraft = SystemPrompts.BASELINE }) { Text("恢复默认") }
                    TextButton(onClick = { editingBaseline = false }) { Text("取消") }
                }
            },
        )
    }

    if (showMemories) {
        AlertDialog(
            onDismissRequest = { showMemories = false },
            title = { Text("长期记忆（${memories.size}）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = memoryFilter == null,
                            onClick = { memoryFilter = null },
                            label = { Text("全部") },
                        )
                        FilterChip(
                            selected = memoryFilter == MemoryType.PROFILE,
                            onClick = { memoryFilter = MemoryType.PROFILE },
                            label = { Text("画像") },
                        )
                        FilterChip(
                            selected = memoryFilter == MemoryType.SEMANTIC,
                            onClick = { memoryFilter = MemoryType.SEMANTIC },
                            label = { Text("事实") },
                        )
                        FilterChip(
                            selected = memoryFilter == MemoryType.EPISODIC,
                            onClick = { memoryFilter = MemoryType.EPISODIC },
                            label = { Text("情景") },
                        )
                    }
                    when {
                        memoryLoading -> Text("读取中…")
                        memories.isEmpty() -> Text(
                            "还没有记忆。开启后，压缩摘要会自动入库；也可以让模型调用 memory_save。",
                        )
                        else -> LazyColumn(
                            modifier = Modifier.heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(memories, key = { it.id }) { record ->
                                MemoryCard(
                                    record = record,
                                    events = memoryEvents[record.id].orEmpty(),
                                    onEdit = {
                                        memoryEditing = record
                                        memoryEditText = record.text
                                        memoryEditMessage = ""
                                    },
                                    onDelete = { memoryDeleting = record },
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showMemories = false }) { Text("关闭") }
            },
        )
    }

    if (memoryDeleting != null) {
        val record = memoryDeleting!!
        AlertDialog(
            onDismissRequest = { memoryDeleting = null },
            title = { Text("删除这条记忆？") },
            text = { Text(record.text, style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(
                    onClick = {
                        memoryDeleting = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                MemoryStore.get(context).delete(record.id)
                            }
                            loadMemories(memoryFilter)
                            onMemoryChanged()
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { memoryDeleting = null }) { Text("取消") }
            },
        )
    }

    if (memoryEditing != null) {
        val record = memoryEditing!!
        AlertDialog(
            onDismissRequest = { memoryEditing = null },
            title = { Text("编辑记忆") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = memoryEditText,
                        onValueChange = {
                            memoryEditText = it
                            memoryEditMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("内容（最多 400 字）") },
                    )
                    if (memoryEditMessage.isNotBlank()) {
                        Text(
                            memoryEditMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                updateMemory(MemoryStore.get(context), record.id, memoryEditText)
                            }
                            if (result.ok) {
                                memoryEditing = null
                                loadMemories(memoryFilter)
                                onMemoryChanged()
                            } else {
                                memoryEditMessage = memoryUpdateMessage(result.reason)
                            }
                        }
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { memoryEditing = null }) { Text("取消") }
            },
        )
    }

    if (confirmClearMemory) {
        AlertDialog(
            onDismissRequest = { confirmClearMemory = false },
            title = { Text("清空全部长期记忆？") },
            text = { Text("条目与审计记录会被删除，会话原文和归档不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearMemory = false
                        onClearMemory()
                    },
                ) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearMemory = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun MemoryCard(
    record: MemoryRecord,
    events: List<MemoryEvent>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "[${record.type.dbName}] ${formatDate(record.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onDelete) { Text("删除") }
            }
            Text(record.text, style = MaterialTheme.typography.bodySmall)
            if (record.sourcePath.isNotBlank()) {
                Text(
                    record.sourcePath,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (events.isNotEmpty()) {
                Text(
                    events.joinToString(" · ") { "${it.action}/${it.actor}" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun memoryUpdateMessage(reason: String): String = when (reason) {
    "not_found" -> "记忆不存在、已过期或已被替代"
    "sensitive" -> "内容疑似包含密钥或凭据"
    "duplicate" -> "与已有记忆重复"
    "too_long" -> "超过 400 字，请拆分"
    else -> "保存失败"
}

private fun formatDate(millis: Long): String =
    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(millis))

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "${bytes / (1024L * 1024)} MB"
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun OptionRow(
    title: String,
    current: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                current,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, label) ->
                AssistChip(
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing()
        }
    }
}
