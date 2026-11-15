/*
 * This file is part of DataArk.
 *
 * Copyright (C) 2026 PILIHU2022
 *
 * DataArk is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * DataArk is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along
 * with DataArk. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dev.dataark.backup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.dataark.backup.core.OperationKind
import dev.dataark.backup.core.OperationResultItem
import dev.dataark.backup.core.OperationState
import dev.dataark.backup.ui.components.StatusBadge
import dev.dataark.backup.ui.components.WarningBanner
import dev.dataark.backup.ui.components.formatBytes

/**
 * Modal progress/results panel driven by [OperationState]. While the operation is running it cannot
 * be dismissed except through the cancel action; once finished the user closes it.
 */
@Composable
fun OperationDialog(
    operation: OperationState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val active = operation.active
    val kindLabel = operation.kind?.label ?: "操作"
    val title = when {
        active -> "${kindLabel}进行中"
        operation.cancelled -> "${kindLabel}已取消"
        operation.fatalError != null -> "${kindLabel}失败"
        else -> "${kindLabel}完成"
    }

    Dialog(
        onDismissRequest = { if (!active) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !active,
            dismissOnClickOutside = !active,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = CircleShape,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (operation.kind == OperationKind.RESTORE) {
                                        Icons.Filled.Restore
                                    } else {
                                        Icons.Filled.Save
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = when {
                                active -> "保持应用在前台或后台运行均可"
                                operation.cancelled -> "已按请求停止"
                                operation.fatalError != null -> operation.fatalError.orEmpty()
                                else -> "共 ${operation.results.size} 项结果"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!active) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    } else if (operation.total <= 0) {
                        StatusBadge(text = "准备中")
                    }
                }

                Spacer(Modifier.height(14.dp))

                if (operation.total > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { operation.progress },
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${operation.completed}/${operation.total}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = operation.currentLabel.ifBlank {
                        if (active) "正在准备…" else "无进行中的应用"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (operation.phase.isNotBlank() || operation.detail.isNotBlank()) {
                    Text(
                        text = listOf(operation.phase, operation.detail)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (operation.currentPackage.isNotBlank()) {
                    Text(
                        text = operation.currentPackage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (operation.fatalError != null) {
                    Spacer(Modifier.height(12.dp))
                    WarningBanner(
                        text = operation.fatalError,
                        icon = Icons.Filled.ErrorOutline,
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResultCounter(
                        count = operation.successCount,
                        label = "成功",
                        color = MaterialTheme.colorScheme.primary,
                        icon = Icons.Filled.CheckCircle,
                    )
                    ResultCounter(
                        count = operation.failureCount,
                        label = "失败",
                        color = MaterialTheme.colorScheme.error,
                        icon = Icons.Filled.ErrorOutline,
                    )
                    ResultCounter(
                        count = operation.results.count { it.skipped },
                        label = "跳过",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Filled.RemoveCircleOutline,
                    )
                }

                if (operation.results.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    ) {
                        LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                            items(items = operation.results) { result ->
                                ResultRow(result)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (active) {
                        OutlinedButton(onClick = onCancel) { Text("取消") }
                    } else {
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCounter(
    count: Int,
    label: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
private fun ResultRow(result: OperationResultItem) {
    val tint = when {
        result.skipped -> MaterialTheme.colorScheme.onSurfaceVariant
        result.success -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                result.skipped -> Icons.Filled.RemoveCircleOutline
                result.success -> Icons.Filled.CheckCircle
                else -> Icons.Filled.ErrorOutline
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = result.label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.message.isNotBlank()) {
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (result.bytes > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatBytes(result.bytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}