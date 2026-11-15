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

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.dataark.backup.BuildConfig
import dev.dataark.backup.R
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.ui.components.InfoLine
import dev.dataark.backup.ui.components.SectionTitle
import dev.dataark.backup.ui.components.StatusBadge
import dev.dataark.backup.ui.components.WarningBanner

@Composable
fun SettingsScreen(
    state: DataArkUiState,
    viewModel: DataArkViewModel,
    onNavigate: (AppTab) -> Unit = {},
) {
    var instructionsFor by remember { mutableStateOf<PrivilegeMode?>(null) }
    val requestStorageAccess = rememberStorageAccessLauncher { viewModel.refreshStorage() }
    val requestNotifications = rememberNotificationPermissionLauncher {
        viewModel.refreshNotificationPermission()
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        item { SectionTitle("特权模式") }
        item {
            Text(
                text = "特权模式决定能读取多少应用数据。切换后会立即检测该模式是否可用；"
                    + "所有授权提示都由用户主动触发。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(PrivilegeMode.entries) { mode ->
            val selected = state.selectedMode == mode
            val status = if (selected) state.privilege else state.modeStatuses[mode]
            PrivilegeModeCard(
                mode = mode,
                selected = selected,
                status = status,
                busy = selected && (state.privilegeBusy || state.privilegeRequesting),
                requesting = selected && state.privilegeRequesting,
                onSelect = { viewModel.selectMode(mode) },
                onAuthorize = { viewModel.requestPrivilege() },
                onRefresh = { viewModel.refreshPrivilege() },
                onInstructions = { instructionsFor = mode },
            )
        }

        item { SectionTitle("存储") }
        item {
            StorageSection(
                state = state,
                onRefresh = { viewModel.refreshStorage() },
                onRequestAccess = requestStorageAccess,
            )
        }

        item { SectionTitle(stringResource(R.string.perm_notifications)) }
        item {
            NotificationSection(
                state = state,
                onRequest = requestNotifications,
            )
        }

        item { SectionTitle("关于") }
        item { AboutSection(state) }
    }

    instructionsFor?.let { mode ->
        InstructionsDialog(mode = mode) { instructionsFor = null }
    }
}

@Composable
private fun PrivilegeModeCard(
    mode: PrivilegeMode,
    selected: Boolean,
    status: dev.dataark.backup.core.PrivilegeStatus?,
    busy: Boolean,
    requesting: Boolean,
    onSelect: () -> Unit,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit,
    onInstructions: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = modeSubtitle(mode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (status != null) {
                    val usable = status.isUsable
                    StatusBadge(
                        text = when {
                            usable -> "可用"
                            status.available -> "未授权"
                            else -> "不可用"
                        },
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
            }
            if (selected) {
                Spacer(Modifier.height(6.dp))
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = status?.title ?: "尚未检测",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = status?.detail.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatusBadge(
                                text = PrivilegeManager.accessLabel(status.access),
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
                        }
                    }
                    if (!status?.error.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        WarningBanner(text = status?.error.orEmpty(), icon = Icons.Filled.Info)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onAuthorize,
                            enabled = mode != PrivilegeMode.NONE && !requesting,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (requesting) "等待授权…" else "授权")
                        }
                        FilledTonalButton(
                            onClick = onRefresh,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(6.dp))
                            } else {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text("刷新状态", maxLines = 1)
                        }
                    }
                    TextButton(onClick = onInstructions, modifier = Modifier.fillMaxWidth()) {
                        Text("查看设置指引")
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageSection(
    state: DataArkUiState,
    onRefresh: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    val storage = state.storage
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "备份根目录",
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
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusBadge(
                        text = if (storage.persistent) "持久位置" else "应用专属位置",
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
                Text(
                    text = if (storage.persistent) {
                        "备份保存在公共存储的 DataArk 目录，卸载应用后仍然保留。"
                    } else {
                        "当前保存在应用专属目录，卸载应用会一并删除。授予所有文件访问权限后可改用公共目录。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRequestAccess) {
                    Text(if (storage.allFilesAccess) "管理所有文件访问" else "授予所有文件访问")
                }
            }
        }
    }
}

@Composable
private fun NotificationSection(
    state: DataArkUiState,
    onRequest: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "备份进度通知",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "前台服务通知用于展示备份/恢复进度，并在完成后提示结果。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.notificationsGranted) {
                    StatusBadge(text = "已允许")
                } else {
                    StatusBadge(
                        text = "已拒绝",
                        container = MaterialTheme.colorScheme.errorContainer,
                        content = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            if (!state.notificationsGranted) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "通知权限被拒绝时，备份仍会在后台进行，但通知栏不显示进度。可在系统设置中重新开启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRequest) { Text("请求通知权限") }
            }
        }
    }
}

@Composable
private fun AboutSection(state: DataArkUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Archive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "数据方舟",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(10.dp))
            InfoLine("Android", Build.VERSION.RELEASE ?: "?")
            InfoLine("当前模式", state.selectedMode.label)
            InfoLine("数据访问", PrivilegeManager.accessLabel(state.privilege.access))
            InfoLine("备份数量", "${state.backups.size} 个")
            Spacer(Modifier.height(10.dp))
            Text(
                text = "GNU General Public License v3.0",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "数据方舟是自由软件：你可以依据自由软件基金会发布的 GNU 通用公共许可证"
                    + "（第 3 版或更高版本）条款重新分发和/或修改它。本程序按“现状”提供，"
                    + "不附带任何担保。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "备份位置由应用内「备份根目录」决定；请自行保管好备份数据。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun modeSubtitle(mode: PrivilegeMode): String = when (mode) {
    PrivilegeMode.ROOT -> "Magisk / KernelSU / APatch，可完整读写应用私有数据"
    PrivilegeMode.SHIZUKU -> "通过 ADB 或无线调试运行，无需 Root；ADB 模式仅 debuggable 数据"
    PrivilegeMode.DHIZUKU -> "Device Owner 能力，主要用于 APK 与外部数据"
    PrivilegeMode.NONE -> "仅浏览应用列表，无法读取私有数据"
}
