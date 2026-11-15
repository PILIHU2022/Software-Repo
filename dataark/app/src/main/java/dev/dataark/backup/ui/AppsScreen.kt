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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.dataark.backup.core.AppEntry
import dev.dataark.backup.core.BackupOptions
import dev.dataark.backup.core.BackupScope
import dev.dataark.backup.core.DataAccess
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.ui.components.AppAvatar
import dev.dataark.backup.ui.components.BadgeRow
import dev.dataark.backup.ui.components.EmptyState
import dev.dataark.backup.ui.components.StatusBadge
import dev.dataark.backup.ui.components.SwitchRow
import dev.dataark.backup.ui.components.WarningBanner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(state: DataArkUiState, viewModel: DataArkViewModel) {
    var showOptions by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    val filtered = remember(state.apps, state.scope, state.query) {
        val query = state.query.trim()
        state.apps.asSequence()
            .filter { app ->
                when (state.scope) {
                    BackupScope.ALL -> true
                    BackupScope.USER -> !app.isSystem
                    BackupScope.SYSTEM -> app.isSystem
                }
            }
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }

    val selectedApps = remember(state.apps, state.selected) {
        state.apps.filter { it.packageName in state.selected }
    }
    val anyPrivate = selectedApps.any { PrivilegeManager.canUsePrivateData(it, state.privilege) }
    val fullAccess = state.privilege.access == DataAccess.FULL
    val canExternal = state.privilege.access == DataAccess.FULL

    fun closeSheet(then: () -> Unit = {}) {
        scope.launch {
            runCatching { sheetState.hide() }
            showOptions = false
            then()
        }
    }

    val appsError = state.appsError

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "选择应用",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "共 ${state.apps.size} 个应用 · 当前列表 ${filtered.size} 个",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.reloadApps() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新应用列表")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索应用名称或包名") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackupScope.entries.forEach { backupScope ->
                    FilterChip(
                        selected = state.scope == backupScope,
                        onClick = { viewModel.setScope(backupScope) },
                        label = { Text(backupScope.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val allSelected = filtered.isNotEmpty() &&
                    filtered.all { it.packageName in state.selected }
                Text(
                    text = "已选 ${state.selected.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (filtered.isNotEmpty()) {
                    TextButton(
                        onClick = {
                            if (allSelected) {
                                viewModel.replaceSelection(
                                    (state.selected - filtered.map { it.packageName }.toSet()).toList(),
                                )
                            } else {
                                viewModel.selectAll(filtered.map { it.packageName })
                            }
                        },
                    ) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }
                if (state.selected.isNotEmpty()) {
                    TextButton(onClick = { viewModel.clearSelection() }) { Text("清除") }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))

        Box(modifier = Modifier.weight(1f)) {
            when {
                state.appsLoading && state.apps.isEmpty() -> LoadingApps()
                appsError != null && state.apps.isEmpty() -> ErrorApps(
                    message = appsError,
                    onRetry = { viewModel.reloadApps() },
                )

                filtered.isEmpty() -> EmptyState(
                    icon = Icons.Filled.Search,
                    title = if (state.query.isBlank()) "没有可显示的应用" else "没有匹配的应用",
                    subtitle = if (state.query.isBlank()) "下拉刷新应用列表后重试" else "换个关键词或筛选条件试试",
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    items(items = filtered, key = { app -> app.packageName }) { app ->
                        val selected = app.packageName in state.selected
                        AppRow(
                            app = app,
                            selected = selected,
                            canReadPrivate = PrivilegeManager.canUsePrivateData(app, state.privilege),
                            icon = viewModel.iconCache[app.packageName],
                            onRequestIcon = viewModel::requestIcon,
                            onToggle = { viewModel.toggleSelected(app.packageName) },
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            tonalElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.selected.isEmpty()) {
                            "请选择要备份的应用"
                        } else {
                            "已选 ${state.selected.size} 个应用"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = if (anyPrivate) {
                            "可备份私有数据、外部数据与 APK"
                        } else if (canExternal) {
                            "当前权限仅可备份外部数据与 APK"
                        } else {
                            "当前权限仅可备份 APK"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = { showOptions = true },
                    enabled = state.selected.isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("备份")
                }
            }
        }
    }

    if (showOptions) {
        ModalBottomSheet(
            onDismissRequest = { showOptions = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            BackupOptionsSheet(
                selectedCount = selectedApps.size,
                anyPrivate = anyPrivate,
                fullAccess = fullAccess,
                canExternal = canExternal,
                onCancel = { closeSheet() },
                onStart = { options ->
                    closeSheet { viewModel.startBackup(options) }
                },
            )
        }
    }
}

@Composable
private fun LoadingApps() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "正在读取应用列表…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorApps(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        WarningBanner(text = message, icon = Icons.Filled.Info)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    selected: Boolean,
    canReadPrivate: Boolean,
    icon: ImageBitmap?,
    onRequestIcon: (String) -> Unit,
    onToggle: () -> Unit,
) {
    LaunchedEffect(app.packageName) { onRequestIcon(app.packageName) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        AppAvatar(bitmap = icon, label = app.label, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            BadgeRow {
                StatusBadge(
                    text = "v${app.versionName.ifBlank { app.versionCode.toString() }}",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.isSystem) {
                    StatusBadge(
                        text = "系统",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (app.isDebuggable) {
                    StatusBadge(
                        text = "可调试",
                        container = MaterialTheme.colorScheme.surface,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canReadPrivate) {
                    StatusBadge(text = "私有数据可读")
                } else {
                    StatusBadge(
                        text = "需 Root",
                        container = MaterialTheme.colorScheme.surfaceVariant,
                        content = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupOptionsSheet(
    selectedCount: Int,
    anyPrivate: Boolean,
    fullAccess: Boolean,
    canExternal: Boolean,
    onCancel: () -> Unit,
    onStart: (BackupOptions) -> Unit,
) {
    var includeInternal by remember { mutableStateOf(anyPrivate) }
    var includeDeviceProtected by remember { mutableStateOf(fullAccess) }
    var includeExternal by remember { mutableStateOf(canExternal) }
    var includeApk by remember { mutableStateOf(true) }
    var compress by remember { mutableStateOf(false) }

    val effectiveInternal = includeInternal && anyPrivate
    val effectiveDeviceProtected = includeDeviceProtected && fullAccess
    val effectiveExternal = includeExternal && canExternal
    val hasSomething = includeApk || effectiveInternal || effectiveExternal

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "备份选项",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(text = "$selectedCount 个应用")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "选择要写入备份的内容；未选择的内容不会占用空间。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        SwitchRow(
            title = "私有数据（/data/user/0）",
            subtitle = if (anyPrivate) {
                "备份账号、设置与数据库；备份时会先停止应用"
            } else {
                "当前权限模式无法读取所选应用的私有数据"
            },
            checked = includeInternal && anyPrivate,
            onCheckedChange = { includeInternal = it },
            enabled = anyPrivate,
        )
        SwitchRow(
            title = "设备加密数据（/data/user_de/0）",
            subtitle = if (fullAccess) {
                "部分应用的 Direct Boot 数据"
            } else {
                "需要完整数据权限（Root 或 Root 模式 Shizuku）"
            },
            checked = includeDeviceProtected && fullAccess,
            onCheckedChange = { includeDeviceProtected = it },
            enabled = fullAccess,
        )
        SwitchRow(
            title = "外部数据（Android/data + obb）",
            subtitle = if (canExternal) {
                "游戏资源包、下载缓存等外部目录"
            } else {
                "当前权限模式无法读取外部数据目录"
            },
            checked = includeExternal && canExternal,
            onCheckedChange = { includeExternal = it },
            enabled = canExternal,
        )
        SwitchRow(
            title = "APK 文件",
            subtitle = "包含 base.apk 与拆分 APK，可用于重装",
            checked = includeApk,
            onCheckedChange = { includeApk = it },
        )
        SwitchRow(
            title = "gzip 压缩",
            subtitle = "备份体积更小，但耗时更长",
            checked = compress,
            onCheckedChange = { compress = it },
        )
        Spacer(Modifier.height(8.dp))
        if (!anyPrivate && !canExternal) {
            WarningBanner(
                text = "当前权限模式只能备份 APK 文件；如需应用数据，请在设置中切换到 Root 或授权 Shizuku。",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
        } else if (!hasSomething) {
            WarningBanner(
                text = "至少需要选择一项备份内容。",
                icon = Icons.Filled.Info,
            )
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onStart(
                        BackupOptions(
                            includeInternalData = effectiveInternal,
                            includeDeviceProtectedData = effectiveDeviceProtected,
                            includeExternalData = effectiveExternal,
                            includeApk = includeApk,
                            compress = compress,
                        ),
                    )
                },
                enabled = hasSomething,
            ) {
                Text("开始备份")
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}