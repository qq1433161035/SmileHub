package com.wbhub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The 工程 page: imported projects, their artifacts and the operations that
 * produced them.
 *
 * This is the read side of the tooling stack. Changes still go through the
 * agent, so every mutation keeps its audit row; the page exists to see what is
 * in the workspace and what happened to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolingScreen(
    state: ToolingState,
    onRefresh: () -> Unit,
    onSelectProject: (String?) -> Unit,
    onDeleteProject: (String) -> Unit,
) {
    val detail = state.selected
    var pendingDelete by remember { mutableStateOf<ToolingProject?>(null) }
    // 全面屏手势：详情页不再画返回箭头，系统返回先回到项目列表。
    BackHandler(enabled = detail != null) { onSelectProject(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (detail == null) "工程" else detail.project.name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                detail != null -> ProjectDetail(detail, onDelete = { pendingDelete = detail.project })
                state.loading && state.projects.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.projects.isEmpty() -> EmptyProjects()
                else -> ProjectList(
                    projects = state.projects,
                    onSelect = onSelectProject,
                    onDelete = { pendingDelete = it },
                )
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除项目？") },
            text = {
                Text("将删除「${project.name}」的数据库记录与工作区文件（原件、解包目录、产物），不可恢复。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteProject(project.id)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyProjects() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("还没有项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "在对话里让 Agent 调用 project_import 导入 APK；导入、解码、构建、签名后的产物与操作记录都会显示在这里。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProjectList(
    projects: List<ToolingProject>,
    onSelect: (String) -> Unit,
    onDelete: (ToolingProject) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(projects, key = { it.id }) { project ->
            Card(
                onClick = { onSelect(project.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            project.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (project.packageName.isNotBlank()) {
                            Text(
                                buildString {
                                    append(project.packageName)
                                    if (project.versionName.isNotBlank()) append(" · ${project.versionName}")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusChip(project.status)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatTime(project.updatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onDelete(project) }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "删除项目",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "查看项目",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectDetail(detail: ToolingProjectDetail, onDelete: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            detail.project.packageName.ifBlank { detail.project.name },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        StatusChip(detail.project.status)
                        IconButton(onClick = onDelete) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除项目",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    if (detail.project.versionName.isNotBlank()) {
                        Text("版本 ${detail.project.versionName}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        detail.workspace,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "项目 ID：${detail.project.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { SectionTitle("产物") }
        if (detail.artifacts.isEmpty()) {
            item { Hint("还没有产物。解码、构建或签名后会出现在这里。") }
        } else {
            items(detail.artifacts) { artifact ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                artifact.kind,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                artifact.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatSize(artifact.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            artifact.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${artifact.origin} · sha256 ${artifact.sha256.take(16)}…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item { SectionTitle("最近操作") }
        if (detail.operations.isEmpty()) {
            item { Hint("还没有操作记录。") }
        } else {
            items(detail.operations) { operation ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                operation.tool,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                operation.status,
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor(operation.status),
                            )
                        }
                        if (operation.summary.isNotBlank()) {
                            Text(
                                operation.summary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            formatTime(operation.startedAt) +
                                (operation.outputRef?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusChip(status: String) {
    val color = statusColor(status)
    Text(
        statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun statusColor(status: String) = when (status) {
    "SIGNED" -> MaterialTheme.colorScheme.primary
    "BUILT" -> MaterialTheme.colorScheme.tertiary
    "FAILED", "TIMEOUT", "DENIED" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun statusLabel(status: String): String = when (status) {
    "NEW" -> "已导入"
    "DECODED" -> "已解码"
    "MODIFIED" -> "已修改"
    "BUILT" -> "已构建"
    "SIGNED" -> "已签名"
    "FAILED" -> "失败"
    "OK" -> "成功"
    "RUNNING" -> "执行中"
    "TIMEOUT" -> "超时"
    "DENIED" -> "已拒绝"
    else -> status
}

private fun formatTime(epochMs: Long): String =
    if (epochMs <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
