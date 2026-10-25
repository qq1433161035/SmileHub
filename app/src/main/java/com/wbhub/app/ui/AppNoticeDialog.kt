package com.wbhub.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.wbhub.app.data.AppNotice

/** 启动公告弹窗；仅保留「不再提示」与可选「查看详情」，不提供确认按钮。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppNoticeDialog(
    notice: AppNotice,
    onDismiss: () -> Unit,
    onDontShow: () -> Unit,
) {
    val context = LocalContext.current
    // 弹窗渲染在 HubApp 之外，必须自带同一套主题，否则会退回 Material 默认配色。
    MaterialExpressiveTheme(
        colorScheme = expressiveLightColorScheme(),
        motionScheme = MotionScheme.expressive(),
    ) {
        val colors = MaterialTheme.colorScheme
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
        ) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = colors.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 16.dp,
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(colors.primaryContainer, colors.tertiaryContainer),
                                ),
                            )
                            .padding(horizontal = 22.dp, vertical = 20.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(colors.surface.copy(alpha = 0.6f), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Campaign,
                                    contentDescription = null,
                                    tint = colors.onPrimaryContainer,
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "新公告",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                                )
                                Text(
                                    text = notice.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    Column(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (notice.time.isNotBlank()) {
                            Surface(shape = CircleShape, color = colors.secondaryContainer) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Event,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = colors.onSecondaryContainer,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = formatNoticeTime(notice.time),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        if (notice.content.isNotBlank()) {
                            Text(
                                text = notice.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                lineHeight = 22.sp,
                                modifier = Modifier
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (notice.actionUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = {
                                        openLink(context, notice.actionUrl)
                                        onDismiss()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Text(notice.actionText.ifBlank { "查看详情" })
                                }
                            }
                            Button(
                                onClick = onDontShow,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                Text("不再提示")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openLink(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun formatNoticeTime(raw: String): String =
    if (raw.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
        raw.replaceFirst("-", "年").replaceFirst("-", "月") + "日"
    } else {
        raw
    }
