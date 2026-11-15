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

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.dataark.backup.R
import dev.dataark.backup.core.BackupRecord
import dev.dataark.backup.core.DataAccess
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.core.StorageInfo
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.ui.components.AppAvatar
import dev.dataark.backup.ui.components.EmptyState
import dev.dataark.backup.ui.components.SectionTitle
import dev.dataark.backup.ui.components.StatTile
import dev.dataark.backup.ui.components.StatusBadge
import dev.dataark.backup.ui.components.WarningBanner
import dev.dataark.backup.ui.components.formatBytes
import dev.dataark.backup.ui.components.formatRelativeTime

@Composable
fun HomeScreen(
    state: DataArkUiState,
    viewModel: DataArkViewModel,
    onNavigate: (AppTab) -> Unit,
) {
    var showInstructions by rememberSaveable { mutableStateOf(false) }
    val requestStorageAccess = rememberStorageAccessLauncher { viewModel.refreshStorage() }
    val recent = state.backups.take(3)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { HeroHeader(state) }
        item {
            PrivilegeCard(
                state = state,
                onRefresh = { viewModel.refreshPrivilege() },
                onAuthorize = { viewModel.requestPrivilege() },
                onInstructions = { showInstructions = true },
            )
        }
        item {
            StorageCard(
                storage = state.storage,
                onRefresh = { viewModel.refreshStorage() },
                onRequestAccess = requestStorageAccess,
            )
        }
        item { StatsRow(state) }
        item {
            SectionTitle(text = "最近备份") {
                TextButton(onClick = { onNavigate(AppTab.BACKUPS) }) {
                    Text("查看全部")
                    Icon(Icons.Filled.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (recent.isEmpty()) {
            item {
                RecentEmptyCard(
                    loading = state.backupsLoading,
                    onGoBackup = { onNavigate(AppTab.APPS) },
                )
            }
        } else {
            items(items = recent, key = { record -> record.id }) { record ->
                LaunchedEffect(record.packageName) { viewModel.requestIcon(record.packageName) }
                RecentBackupCard(
                    record = record,
                    icon = viewModel.iconCache[record.packageName],
                    onClick = { onNavigate(AppTab.BACKUPS) },
                )
            }
        }
    }

    if (showInstructions) {
        InstructionsDialog(mode = state.selectedMode) { showInstructions = false }
    }
}

@Composable
private fun HeroHeader(state: DataArkUiState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
                    ),
                ),
            )
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Archive,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "本地离线备份 Android 应用数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(text = state.selectedMode.label)
                StatusBadge(
                    text = PrivilegeManager.accessLabel(state.privilege.access),
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusBadge(
                    text = "已备份 ${state.backups.size}",
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PrivilegeCard(
    state: DataArkUiState,
    onRefresh: () -> Unit,
    onAuthorize: () -> Unit,
    onInstructions: () -> Unit,
) {
    val status = state.privilege
    val usable = status.isUsable
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "特权状态",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(
                    text = if (usable) "可用" else "不可用",
                    container = if (usable) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    content = if (usable) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = status.title.ifBlank { state.selectedMode.label },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    text = status.mode.label,
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.uid != null) {
                    StatusBadge(
                        text = "UID ${status.uid}",
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(
                    text = PrivilegeManager.accessLabel(status.access),
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!status.error.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                WarningBanner(text = status.error, icon = Icons.Filled.Warning)
            }
            if (state.selectedMode == PrivilegeMode.NONE) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "当前为无特权模式：只能备份 APK 文件，无法读取应用私有数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onRefresh, enabled = !state.privilegeBusy) {
                    if (state.privilegeBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("刷新")
                }
                Button(
                    onClick = onAuthorize,
                    enabled = state.selectedMode != PrivilegeMode.NONE && !state.privilegeRequesting,
                ) {
                    Text(if (state.privilegeRequesting) "等待授权…" else "授权")
                }
                TextButton(onClick = onInstructions) { Text("查看指引") }
            }
        }
    }
}

@Composable
private fun StorageCard(
    storage: StorageInfo?,
    onRefresh: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.label_storage),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRefresh) { Text("刷新") }
            }
            if (storage == null) {
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
            SelectionContainer {
                Text(
                    text = storage.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBadge(
                    text = if (storage.persistent) "持久目录" else "应用专属目录",
                    container = if (storage.persistent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    content = if (storage.persistent) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                StatusBadge(
                    text = if (storage.writable) "可写" else "只读",
                    container = MaterialTheme.colorScheme.surface,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StatusBadge(
                    text = if (storage.allFilesAccess) "所有文件权限已授予" else "所有文件权限未授予",
                    container = if (storage.allFilesAccess) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
                    content = if (storage.allFilesAccess) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onErrorContainer
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            if (!storage.allFilesAccess) {
                Text(
                    text = "未授予「所有文件访问权限」时，备份会写入应用专属目录，卸载应用后备份将丢失。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
            FilledTonalButton(onClick = onRequestAccess) {
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (storage.allFilesAccess) "管理所有文件访问" else "授予所有文件访问")
            }
            }
        }
    }
}

@Composable
private fun StatsRow(state: DataArkUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile(
            value = state.installedCount.toString(),
            label = stringResource(R.string.stat_apps),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = state.backups.size.toString(),
            label = stringResource(R.string.stat_backups),
            modifier = Modifier.weight(1f),
        )
        StatTile(
            value = formatBytes(state.backupTotalBytes),
            label = stringResource(R.string.stat_size),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RecentEmptyCard(loading: Boolean, onGoBackup: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(
                icon = Icons.Filled.Archive,
                title = if (loading) "正在读取备份…" else "还没有备份",
                subtitle = if (loading) "稍等片刻" else "前往「应用」选择应用，开始第一次备份",
            )
            Button(onClick = onGoBackup, modifier = Modifier.padding(bottom = 16.dp)) {
                Text("去备份")
            }
        }
    }
}

@Composable
private fun RecentBackupCard(
    record: BackupRecord,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(
                        text = privilegeModeLabel(record.mode),
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusBadge(
                        text = formatBytes(record.sizeBytes),
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatRelativeTime(record.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}