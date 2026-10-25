package com.wbhub.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.DialogProperties
import com.wbhub.app.proto.HubModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
/**
 * The built-in agent: a transcript, a prompt box, and the settings that shape a
 * request.
 *
 * Reasoning and tool traffic are collapsed by default. They are the bulk of a
 * long run and are only interesting when something looks wrong, so the visible
 * transcript stays close to the conversation itself until one is opened.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentScreen(
    agent: AgentState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectContext: (Int) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onAnswerApproval: (Boolean) -> Unit,
    onStop: () -> Unit,
    onImagePicked: (String?) -> Unit,
) {
    val listState = rememberLazyListState()
    // Follow the newest line as it streams in, which is the only way the tail
    // of a long answer stays visible without scrolling by hand.
    LaunchedEffect(agent.entries.size, agent.entries.lastOrNull()?.text?.length) {
        if (agent.entries.isNotEmpty()) listState.animateScrollToItem(agent.entries.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (agent.entries.isEmpty()) {
                item { EmptyHint(agent) }
            }
            itemsIndexed(agent.entries) { _, entry -> EntryRow(entry) }
        }
        Composer(
            agent = agent,
            models = models,
            onInputChange = onInputChange,
            onSend = onSend,
            onSelectModel = onSelectModel,
            onSelectEffort = onSelectEffort,
            onSelectContext = onSelectContext,
            onNewSession = onNewSession,
            onOpenSession = onOpenSession,
            onDeleteSession = onDeleteSession,
            onStop = onStop,
            onImagePicked = onImagePicked,
        )
    }

    // The prompt sits above everything so an answer is never hidden behind the
    // transcript the model is still appending to.
    agent.pendingApproval?.let { summary ->
        ApprovalDialog(summary = summary, onAnswer = onAnswerApproval)
    }
}

/**
 * The prompt box with everything that shapes a request attached to it.
 *
 * The settings sit in the same block as the input rather than above the
 * transcript, so the conversation keeps the height instead of a stack of
 * controls that are only consulted once in a while.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Composer(
    agent: AgentState,
    models: List<HubModel>,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String) -> Unit,
    onSelectContext: (Int) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onStop: () -> Unit,
    onImagePicked: (String?) -> Unit,
) {
    val selected = models.firstOrNull { it.id == agent.modelId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val dataUrl = withContext(Dispatchers.IO) { imageDataUrl(context, uri) }
            if (dataUrl != null) {
                onImagePicked(dataUrl)
            } else {
                Toast.makeText(context, "读取图片失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // One scrolling row keeps the chips from wrapping into a second line on
        // a narrow screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipPicker(
                value = agent.sessionTitle.ifBlank { "新会话" },
                options = buildList<Pair<String, String>> {
                    // A fresh conversation is the action taken most often, so it
                    // belongs at the top of the list rather than beside it.
                    add(NEW_SESSION to "＋ 新会话")
                    agent.sessions.forEach { session ->
                        val marker = if (session.compaction.compactedMessages > 0) " ·已压缩" else ""
                        add(session.id to session.title + marker)
                    }
                },
                onSelect = { picked ->
                    if (picked == NEW_SESSION) onNewSession() else onOpenSession(picked)
                },
                onDelete = onDeleteSession,
            )
            ChipPicker(
                value = selected?.name ?: agent.modelId.ifBlank { "选择模型" },
                options = models.map { it.id to it.name },
                onSelect = onSelectModel,
            )
            if (selected != null && selected.supportsEffort) {
                val effortOptions = buildList {
                    // "off" is only offered when the model allows it: an
                    // unsupported value is refused rather than ignored.
                    if (selected.canDisableThinking) add(EFFORT_OFF to "不思考")
                    selected.efforts.forEach { add(it to it) }
                }
                ChipPicker(
                    value = if (agent.effort.isBlank() || agent.effort == EFFORT_OFF) "不思考" else agent.effort,
                    options = effortOptions,
                    onSelect = onSelectEffort,
                )
            }
            if (selected != null && selected.hasContextChoice) {
                ChipPicker(
                    value = formatContext(agent.contextWindow.takeIf { it > 0 } ?: selected.contextWindow),
                    options = selected.contextOptions.map { it.toString() to formatContext(it) },
                    onSelect = { picked -> onSelectContext(picked.toIntOrNull() ?: 0) },
                )
            }
        }

        // 附带的图片先给一眼预览，发送时随消息一起带给模型。
        agent.pendingImage?.let { dataUrl ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val preview = remember(dataUrl) { previewBitmap(dataUrl) }
                if (preview != null) {
                    Image(
                        bitmap = preview,
                        contentDescription = "待发送图片",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp),
                    )
                }
                Text(
                    "图片将随下一条消息发送",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                TextButton(onClick = { onImagePicked(null) }) { Text("移除") }
            }
        }

        // Input and send share one row so the button reads as part of the box.
        Row(verticalAlignment = Alignment.Bottom) {
            IconButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.Default.Image, contentDescription = "添加图片")
            }
            OutlinedTextField(
                value = agent.input,
                onValueChange = onInputChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp, max = 140.dp),
                placeholder = { Text("让 Agent 做什么…") },
                maxLines = 6,
            )
            Spacer(Modifier.width(6.dp))
            if (agent.running) {
                FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = onSend,
                    enabled = (agent.input.isNotBlank() || agent.pendingImage != null) && agent.modelId.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

/**
 * A compact dropdown rendered as a chip.
 *
 * A labelled row would take two lines each, and there are several of these
 * above a small prompt box.
 */
@Composable
private fun ChipPicker(
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    onDelete: ((String) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            // 会话标题就是首条提问，可能很长；不截断会把整行撑满、
            // 把模型/思考等后面的选择器挤出屏幕。
            label = {
                Text(
                    value,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        open = false
                        onSelect(id)
                    },
                    trailingIcon = if (onDelete != null && id != NEW_SESSION) {
                        {
                            IconButton(onClick = { onDelete(id) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除会话",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Copies a transcript line and lets the user know it happened. */
@Composable
private fun rememberCopyToClipboard(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { text ->
            if (text.isNotBlank()) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("对话记录", text))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** One transcript line, styled by who produced it. */
@Composable
private fun EntryRow(entry: AgentEntry) {
    when (entry.role) {
        // Reasoning and tool traffic are the verbose parts of a run, so they are
        // collapsed into a single line that opens on demand.
        AgentEntry.Role.REASONING,
        AgentEntry.Role.TOOL,
        AgentEntry.Role.COMPACT,
        -> CollapsibleRow(entry)

        else -> MessageRow(entry)
    }
}

/** A user or assistant line, always visible. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(entry: AgentEntry) {
    val scheme = MaterialTheme.colorScheme
    val copy = rememberCopyToClipboard()
    val (title, color) = when (entry.role) {
        AgentEntry.Role.USER -> "你" to scheme.primary
        AgentEntry.Role.ASSISTANT -> "Agent" to scheme.onSurface
        AgentEntry.Role.ERROR -> "错误" to scheme.error
        else -> "" to scheme.onSurface
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 长按整条消息复制原文；对话里经常要拿命令或路径去别处用。
            .combinedClickable(onClick = {}, onLongClick = { copy(entry.text) }),
    ) {
        Column(Modifier.padding(10.dp), Arrangement.spacedBy(4.dp)) {
            if (title.isNotEmpty()) {
                Text(title, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
            }
            Text(entry.text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A reasoning or tool line, folded to one row until it is tapped. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsibleRow(entry: AgentEntry) {
    var expanded by remember(entry.text) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val copy = rememberCopyToClipboard()
    val isReasoning = entry.role == AgentEntry.Role.REASONING
    val label = when (entry.role) {
        AgentEntry.Role.REASONING -> "思考"
        AgentEntry.Role.COMPACT -> "压缩"
        else -> "工具"
    }
    val color = when (entry.role) {
        AgentEntry.Role.REASONING -> scheme.onSurfaceVariant
        AgentEntry.Role.COMPACT -> scheme.secondary
        else -> scheme.tertiary
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { expanded = !expanded },
                        onLongClick = { copy(entry.text) },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    // The first line is the summary shown while folded, which is
                    // enough to tell what happened without opening it.
                    entry.text.lineSequence().firstOrNull().orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                )
            }
        }
    }
}

/** Shown before the first prompt. */
@Composable
private fun EmptyHint(agent: AgentState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("内置 Agent", style = MaterialTheme.typography.titleMedium)
        Text(
            "可以直接读写文件、执行命令。命令默认以应用身份运行，" +
                "开启 root 后可访问整个设备。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!agent.storageGranted) {
            Text(
                "尚未授予所有文件访问权限，外部目录可能无法访问。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** Renders a context length the way the model catalogue states it. */
private fun formatContext(tokens: Int): String = when {
    tokens <= 0 -> "默认"
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
    tokens >= 1000 -> "${tokens / 1000}K"
    else -> tokens.toString()
}

/**
 * Asks whether one tool call may proceed.
 *
 * Only the two buttons close this: tapping outside or pressing back must not
 * decide anything, because a vanished dialog used to be read as a refusal and
 * killed the run.
 */
@Composable
private fun ApprovalDialog(summary: String, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text("允许此操作？") },
        text = {
            // 长按可选、可复制：用户经常需要把命令贴出来核对。
            SelectionContainer {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("允许") } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("拒绝") } },
    )
}

/** Sentinel for "thinking off" that is distinct from an unset effort. */
const val EFFORT_OFF = "off"

/** 读取相册图片，缩到模型够用的尺寸后编成 data URL。 */
private fun imageDataUrl(context: Context, uri: Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val maxSide = 1568
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
    val scaled = if (scale < 1f) {
        Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    } else {
        bitmap
    }
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
    "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

/** 把待发送的 data URL 解成缩略图。 */
private fun previewBitmap(dataUrl: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(dataUrl.substringAfter("base64,", ""), Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** Sentinel id for "start a new conversation" in the session picker. */
const val NEW_SESSION = "__new__"
