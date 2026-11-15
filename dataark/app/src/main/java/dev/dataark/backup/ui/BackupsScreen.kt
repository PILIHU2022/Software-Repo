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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import dev.dataark.backup.core.BackupRecord
import dev.dataark.backup.core.VerifyResult
import dev.dataark.backup.core.VerifyState
import dev.dataark.backup.ui.components.AppAvatar
import dev.dataark.backup.ui.components.BadgeRow
import dev.dataark.backup.ui.components.EmptyState
import dev.dataark.backup.ui.components.InfoLine
import dev.dataark.backup.ui.components.StatusBadge
import dev.dataark.backup.ui.components.formatBytes
import dev.dataark.backup.ui.components.formatRelativeTime
import dev.dataark.backup.ui.components.formatTimestamp

@Composable
fun BackupsScreen(
    state: DataArkUiState,
    viewModel: DataArkViewModel,
    onNavigate: (AppTab) -> Unit = {},
) {
    var detail by remember { mutableStateOf<BackupRecord?>(null) }
    var confirmRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var confirmDelete by remember { mutableStateOf<BackupRecord?>(null) }

    val filtered = remember(state.backups, state.backupQuery) {
        val query = state.backupQuery.trim()
        state.backups.filter { record ->
            query.isBlank() ||
                record.label.contains(query, ignoreCase = true) ||
                record.packageName.contains(query, ignoreCase = true) ||
                record.meta.note?.contains(query, ignoreCase = true) == true
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "备份",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "共 ${state.backups.size} 个 · ${formatBytes(state.backupTotalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.reloadBackups() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新备份列表")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.backupQuery,
                onValueChange = viewModel::setBackupQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索备份") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.backupQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setBackupQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.backupsLoading && state.backups.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在读取备份…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.backups.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Archive,
                        title = "还没有备份",
                        subtitle = "前往「应用」选择应用，开始第一次备份",
                    )
                    Button(onClick = { onNavigate(AppTab.APPS) }) { Text("去备份") }
                }

                filtered.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = "没有匹配的备份",
                    subtitle = "换个关键词试试",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items = filtered, key = { record -> record.id }) { record ->
                        LaunchedEffect(record.packageName) { viewModel.requestIcon(record.packageName) }
                        BackupCard(
                            record = record,
                            icon = viewModel.iconCache[record.packageName],
                            onClick = { detail = record },
                        )
                    }
                }
            }
        }
    }

    detail?.let { record ->
        BackupDetailDialog(
            record = record,
            icon = viewModel.iconCache[record.packageName],
            verifyResult = state.verifyResults[record.id],
            verifying = state.verifyingId == record.id,
            onVerify = { viewModel.verify(record) },
            onRestore = { confirmRestore = record },
            onDelete = { confirmDelete = record },
            onDismiss = { detail = null },
        )
    }

    confirmRestore?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复 ${record.label}？") },
            text = {
                Text(
                    "恢复会覆盖已安装应用的数据目录，且需应用已安装（版本尽量一致）。"
                        + "恢复前建议先对当前应用做一次备份。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRestore = null
                        detail = null
                        viewModel.restore(record)
                    },
                ) { Text("继续恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = null }) { Text("取消") }
            },
        )
    }

    confirmDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除备份？") },
            text = {
                Text("将永久删除 ${record.label} 于 ${formatTimestamp(record.createdAt)} 的备份目录及归档文件，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        detail = null
                        viewModel.deleteBackup(record)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BackupCard(
    record: BackupRecord,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    onClick: () -> Unit,
) {
    val meta = record.meta
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppAvatar(bitmap = icon, label = record.label)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = record.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = record.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatRelativeTime(record.createdAt)} · v${meta.versionName.ifBlank { meta.versionCode.toString() }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatBytes(record.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            BadgeRow {
                StatusBadge(
                    text = privilegeModeLabel(record.mode),
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta.hasInternalData) StatusBadge(text = "私有数据")
                if (meta.hasDeviceProtectedData) StatusBadge(text = "设备加密数据")
                if (meta.hasExternalData) StatusBadge(text = "外部数据")
                if (meta.hasApk) {
                    StatusBadge(
                        text = "${meta.apkFiles.size} 个 APK",
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (meta.compression == "gzip") {
                    StatusBadge(
                        text = "gzip",
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!meta.note.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = meta.note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BackupDetailDialog(
    record: BackupRecord,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    verifyResult: VerifyResult?,
    verifying: Boolean,
    onVerify: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val meta = record.meta
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(bitmap = icon, label = record.label, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = record.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = record.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    InfoLine("版本", "${meta.versionName.ifBlank { "?" }} (${meta.versionCode})")
                    InfoLine("模式", privilegeModeLabel(record.mode))
                    InfoLine("创建时间", formatTimestamp(meta.createdAt))
                    InfoLine("总大小", formatBytes(record.sizeBytes))
                    InfoLine(
                        "私有数据",
                        if (meta.hasInternalData) "已备份 · ${formatBytes(meta.dataArchiveBytes)}" else "无",
                    )
                    InfoLine("设备加密数据", if (meta.hasDeviceProtectedData) "已备份" else "无")
                    InfoLine(
                        "外部数据",
                        if (meta.hasExternalData) "已备份 · ${formatBytes(meta.externalArchiveBytes)}" else "无",
                    )
                    InfoLine("APK", if (meta.hasApk) "${meta.apkFiles.size} 个" else "无")
                    InfoLine("压缩", if (meta.compression == "gzip") "gzip" else "无")
                    InfoLine("设备", "${meta.deviceModel} · Android ${meta.androidRelease}")
                    InfoLine("SDK", "最小 ${meta.minSdk} / 目标 ${meta.targetSdk}")
                    SelectionContainer {
                        InfoLine("备份目录", record.dir.absolutePath, mono = true)
                    }
                    if (!meta.note.isNullOrBlank()) {
                        InfoLine("备注", meta.note.orEmpty())
                    }

                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val ok = verifyResult?.state == VerifyState.OK
                            Icon(
                                imageVector = if (verifyResult == null) {
                                    Icons.Filled.Refresh
                                } else if (ok) {
                                    Icons.Filled.CheckCircle
                                } else {
                                    Icons.Filled.ErrorOutline
                                },
                                contentDescription = null,
                                tint = when {
                                    verifyResult == null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    ok -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = verifyResult?.message ?: "尚未校验（SHA-256）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.weight(1f))
                    if (verifying) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onVerify, enabled = !verifying) { Text("校验") }
                    Button(onClick = onRestore) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("恢复")
                    }
                }
            }
        }
    }
}