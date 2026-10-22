package com.wbhub.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.wbhub.app.agent.AgentTools
import com.wbhub.app.agent.ApprovalMode
import com.wbhub.app.storage.AppStorage
import com.wbhub.app.tools.adb.AdbPairing
import com.wbhub.app.tools.adb.AdbSession
import kotlinx.coroutines.Dispatchers
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
    onCompactNow: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onRequestStorage: () -> Unit,
) {
    var confirmAllowAll by remember { mutableStateOf(false) }
    var editingRounds by remember { mutableStateOf(false) }
    var roundsInput by remember { mutableStateOf(agent.maxRounds.toString()) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { AppStorage.prefs(context, "adb") }
    var rootAvailable by remember { mutableStateOf(agent.rootAvailable) }
    var rootMessage by remember { mutableStateOf("") }
    var adbAuto by remember { mutableStateOf(prefs.getBoolean("auto", false)) }
    var adbHint by remember { mutableStateOf("") }
    val adbState by AdbSession.state.collectAsState()

    LaunchedEffect(agent.rootAvailable) { rootAvailable = agent.rootAvailable }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionLabel("工具执行") }

        item {
            SettingRow(
                title = "不再弹窗",
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
                                withContext(Dispatchers.IO) { AdbSession.resetKeys(context) }
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

        item { SectionLabel("工作目录") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    agent.workDir,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }

        item { SectionLabel("配置目录") }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    AppStorage.root(LocalContext.current).absolutePath,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }

    if (confirmAllowAll) {
        AlertDialog(
            onDismissRequest = { confirmAllowAll = false },
            title = { Text("允许所有工具直接执行？") },
            text = {
                Text("开启后写文件、执行 shell、解码/重打包、签名都不会再弹窗。仅在明确知道当前任务要做什么时开启。")
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
