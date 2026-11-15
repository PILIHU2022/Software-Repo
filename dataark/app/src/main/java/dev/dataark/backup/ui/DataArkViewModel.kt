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

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.dataark.backup.core.AppEntry
import dev.dataark.backup.core.AppRepository
import dev.dataark.backup.core.BackupOptions
import dev.dataark.backup.core.BackupRecord
import dev.dataark.backup.core.BackupRepository
import dev.dataark.backup.core.BackupScope
import dev.dataark.backup.core.DataAccess
import dev.dataark.backup.core.OperationBus
import dev.dataark.backup.core.OperationKind
import dev.dataark.backup.core.OperationState
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.core.PrivilegeStatus
import dev.dataark.backup.core.StorageInfo
import dev.dataark.backup.core.StorageManager
import dev.dataark.backup.core.VerifyResult
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.service.BackupService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Single source of truth for the whole Compose UI. All blocking repository/service work runs on
 * [Dispatchers.IO]; the UI only ever observes [state].
 */
class DataArkViewModel(application: android.app.Application) : AndroidViewModel(application) {

    private val appContext: Context get() = getApplication()

    private val appRepository = AppRepository(application)
    private val backupRepository = BackupRepository(application)

    private val _state = MutableStateFlow(DataArkUiState())
    val state: StateFlow<DataArkUiState> = _state.asStateFlow()

    private val messagesChannel = Channel<String>(Channel.BUFFERED)
    val messages = messagesChannel.receiveAsFlow()

    /** package name -> decoded icon. Mutated from the main thread only. */
    private val icons = mutableStateMapOf<String, ImageBitmap>()
    val iconCache: Map<String, ImageBitmap> get() = icons
    private val iconRequests = ConcurrentHashMap.newKeySet<String>()

    private var initialised = false
    private var lastOperation: OperationState = OperationState()

    init {
        viewModelScope.launch {
            PrivilegeManager.selectedMode.collect { mode ->
                _state.update { it.copy(selectedMode = mode) }
            }
        }
        viewModelScope.launch {
            PrivilegeManager.status.collect { status ->
                _state.update { current ->
                    current.copy(
                        privilege = status,
                        modeStatuses = current.modeStatuses + (status.mode to status),
                    )
                }
            }
        }
        viewModelScope.launch {
            OperationBus.state.collect { operation ->
                val previous = lastOperation
                lastOperation = operation
                _state.update { it.copy(operation = operation) }
                if (operation.finished && !previous.finished) {
                    // Refresh the backup list as soon as a backup/restore finishes.
                    reloadBackups(showErrors = false)
                }
            }
        }
        bootstrap()
    }

    // ---------------------------------------------------------------------------------------------
    // Loading
    // ---------------------------------------------------------------------------------------------

    private fun bootstrap() {
        if (initialised) return
        initialised = true
        viewModelScope.launch { reloadApps() }
        viewModelScope.launch { reloadBackups() }
        viewModelScope.launch { refreshStorage() }
        viewModelScope.launch { refreshNotificationPermission() }
        refreshPrivilege(showMessage = false)
    }

    fun reloadApps() {
        viewModelScope.launch {
            _state.update { it.copy(appsLoading = true, appsError = null) }
            val result = runCatching {
                withContext(Dispatchers.IO) { appRepository.load(BackupScope.ALL) }
            }
            result.onSuccess { apps ->
                _state.update { current ->
                    current.copy(
                        apps = apps,
                        appsLoading = false,
                        selected = current.selected.filterTo(mutableSetOf()) { pkg ->
                            apps.any { it.packageName == pkg }
                        },
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(appsLoading = false, appsError = error.message ?: "无法读取应用列表") }
                toast("读取应用列表失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun reloadBackups(showErrors: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(backupsLoading = true) }
            val result = runCatching {
                withContext(Dispatchers.IO) { backupRepository.listBackups() }
            }
            result.onSuccess { records ->
                _state.update { it.copy(backups = records, backupsLoading = false) }
            }.onFailure { error ->
                _state.update { it.copy(backupsLoading = false) }
                if (showErrors) toast("读取备份失败：${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    fun refreshStorage() {
        viewModelScope.launch {
            val info = runCatching { withContext(Dispatchers.IO) { backupRepository.storageInfo() } }
                .getOrNull()
            if (info != null) {
                _state.update { it.copy(storage = info) }
            }
        }
    }

    fun refreshNotificationPermission() {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        _state.update { it.copy(notificationsGranted = granted) }
    }

    /** Called from [android.app.Activity.onResume] so storage/notification state stays fresh. */
    fun onResumed() {
        refreshStorage()
        refreshNotificationPermission()
        viewModelScope.launch {
            val status = runCatching { PrivilegeManager.refresh() }.getOrNull()
            if (status != null) {
                _state.update {
                    it.copy(privilege = status, modeStatuses = it.modeStatuses + (status.mode to status))
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Privilege
    // ---------------------------------------------------------------------------------------------

    fun selectMode(mode: PrivilegeMode) {
        if (_state.value.selectedMode == mode) return
        PrivilegeManager.setSelectedMode(mode)
        _state.update { it.copy(selectedMode = mode) }
        viewModelScope.launch {
            val status = runCatching { PrivilegeManager.probe(mode) }.getOrNull() ?: return@launch
            _state.update {
                it.copy(privilege = status, modeStatuses = it.modeStatuses + (mode to status))
            }
            toast("${mode.label}：${status.title}")
        }
    }

    fun refreshPrivilege(showMessage: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(privilegeBusy = true) }
            val status = runCatching { PrivilegeManager.refresh() }.getOrNull()
            if (status != null) {
                _state.update {
                    it.copy(
                        privilege = status,
                        privilegeBusy = false,
                        modeStatuses = it.modeStatuses + (status.mode to status),
                    )
                }
                if (showMessage) toast("${status.mode.label}：${status.title}")
            } else {
                _state.update { it.copy(privilegeBusy = false) }
                if (showMessage) toast("权限检测失败")
            }
        }
    }

    fun requestPrivilege() {
        val mode = _state.value.selectedMode
        if (mode == PrivilegeMode.NONE) {
            toast("当前为无特权模式，无需授权")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(privilegeBusy = true, privilegeRequesting = true) }
            val status = runCatching { PrivilegeManager.requestPermission(mode) }.getOrNull()
            if (status != null) {
                _state.update {
                    it.copy(
                        privilege = status,
                        privilegeBusy = false,
                        privilegeRequesting = false,
                        modeStatuses = it.modeStatuses + (status.mode to status),
                    )
                }
                toast("${mode.label}：${status.title}")
            } else {
                _state.update { it.copy(privilegeBusy = false, privilegeRequesting = false) }
                toast("授权请求未完成")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // App selection / filtering
    // ---------------------------------------------------------------------------------------------

    fun setScope(scope: BackupScope) {
        _state.update { it.copy(scope = scope) }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun toggleSelected(packageName: String) {
        _state.update { current ->
            val selected = if (packageName in current.selected) {
                current.selected - packageName
            } else {
                current.selected + packageName
            }
            current.copy(selected = selected)
        }
    }

    fun selectAll(packages: List<String>) {
        _state.update { it.copy(selected = it.selected + packages) }
    }

    fun replaceSelection(packages: List<String>) {
        _state.update { it.copy(selected = packages.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun canUsePrivateData(app: AppEntry): Boolean =
        PrivilegeManager.canUsePrivateData(app, _state.value.privilege)

    // ---------------------------------------------------------------------------------------------
    // Icons
    // ---------------------------------------------------------------------------------------------

    /**
     * Decodes the launcher icon for [packageName] on an IO thread and caches it. Requests are
     * de-duplicated, and each caller only asks once per visible list row.
     */
    fun requestIcon(packageName: String) {
        if (icons.containsKey(packageName)) return
        if (!iconRequests.add(packageName)) return
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeIcon(packageName) }
            if (bitmap != null) icons[packageName] = bitmap
            iconRequests.remove(packageName)
        }
    }

    private fun decodeIcon(packageName: String): ImageBitmap? = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        val drawable = pm.getApplicationIcon(info)
        val density = appContext.resources.displayMetrics.density
        val px = (ICON_DP * density).toInt().coerceIn(72, 168)
        drawable.toBitmap(width = px, height = px, config = Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }.getOrNull()

    // ---------------------------------------------------------------------------------------------
    // Operations
    // ---------------------------------------------------------------------------------------------

    fun startBackup(options: BackupOptions) {
        val current = _state.value
        if (current.selected.isEmpty()) {
            toast("请先选择要备份的应用")
            return
        }
        val packages = current.selected.toList()
        viewModelScope.launch {
            val storage = withContext(Dispatchers.IO) { StorageManager.storageInfo(appContext) }
            _state.update { it.copy(storage = storage) }
            val needsPersistent = options.includeInternalData ||
                options.includeDeviceProtectedData ||
                options.includeExternalData
            if (needsPersistent && !storage.persistent) {
                toast("数据备份需要「所有文件访问权限」并把备份写入 /sdcard/DataArk；请先在首页授权")
                return@launch
            }
            BackupService.startBackup(appContext, packages, options)
            _state.update { it.copy(selected = emptySet()) }
            toast("已开始备份 ${packages.size} 个应用")
        }
    }

    fun restore(record: BackupRecord) {
        BackupService.startRestore(appContext, record.id)
        toast("已开始恢复 ${record.label}")
    }

    fun cancelOperation() {
        OperationBus.requestCancel()
        toast("正在取消，当前应用完成后停止")
    }

    /** Closes the finished-operation panel and refreshes the data it may have changed. */
    fun dismissOperation() {
        val operation = _state.value.operation
        OperationBus.reset()
        _state.update { it.copy(operation = OperationState()) }
        lastOperation = OperationState()
        if (operation.finished) {
            val summary = buildString {
                append(operation.kind?.label ?: "操作")
                append(if (operation.cancelled) "已取消" else if (operation.fatalError != null) "失败" else "完成")
                append(" · 成功 ${operation.successCount}")
                if (operation.failureCount > 0) append(" · 失败 ${operation.failureCount}")
            }
            toast(summary)
            reloadBackups(showErrors = false)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Backup records
    // ---------------------------------------------------------------------------------------------

    fun verify(record: BackupRecord) {
        viewModelScope.launch {
            _state.update { it.copy(verifyingId = record.id) }
            val result = runCatching {
                withContext(Dispatchers.IO) { backupRepository.verify(record) }
            }.getOrNull()
            _state.update { current ->
                current.copy(
                    verifyingId = null,
                    verifyResults = if (result != null) {
                        current.verifyResults + (record.id to result)
                    } else {
                        current.verifyResults
                    },
                )
            }
            if (result == null) {
                toast("校验失败：无法读取备份")
            } else {
                toast(if (result.state == dev.dataark.backup.core.VerifyState.OK) {
                    "校验通过：${record.label}"
                } else {
                    "校验未通过：${result.message}"
                })
            }
        }
    }

    fun deleteBackup(record: BackupRecord) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { backupRepository.delete(record) }
            _state.update { current ->
                current.copy(verifyResults = current.verifyResults - record.id)
            }
            if (deleted) {
                toast("已删除 ${record.label} 的备份")
                reloadBackups(showErrors = false)
            } else {
                toast("删除失败：${record.dir.absolutePath}")
            }
        }
    }

    fun setBackupQuery(query: String) {
        _state.update { it.copy(backupQuery = query) }
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private fun toast(message: String) {
        messagesChannel.trySend(message)
    }

    private companion object {
        const val ICON_DP = 48f
    }
}

data class DataArkUiState(
    val apps: List<AppEntry> = emptyList(),
    val appsLoading: Boolean = true,
    val appsError: String? = null,
    val scope: BackupScope = BackupScope.USER,
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val backups: List<BackupRecord> = emptyList(),
    val backupsLoading: Boolean = true,
    val backupQuery: String = "",
    val storage: StorageInfo? = null,
    val privilege: PrivilegeStatus = PrivilegeStatus(
        mode = PrivilegeMode.ROOT,
        available = false,
        permissionGranted = false,
        access = DataAccess.NONE,
        title = "检测中…",
        detail = "正在检测特权状态",
    ),
    val selectedMode: PrivilegeMode = PrivilegeMode.ROOT,
    val modeStatuses: Map<PrivilegeMode, PrivilegeStatus> = emptyMap(),
    val privilegeBusy: Boolean = false,
    val privilegeRequesting: Boolean = false,
    val notificationsGranted: Boolean = true,
    val operation: OperationState = OperationState(),
    val verifyingId: String? = null,
    val verifyResults: Map<String, VerifyResult> = emptyMap(),
) {
    val backupTotalBytes: Long get() = backups.sumOf { it.sizeBytes }
    val installedCount: Int get() = apps.size
    val kind: OperationKind? get() = operation.kind
}