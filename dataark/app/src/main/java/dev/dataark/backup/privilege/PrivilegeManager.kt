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

package dev.dataark.backup.privilege

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import com.topjohnwu.superuser.Shell
import dev.dataark.backup.core.AppEntry
import dev.dataark.backup.core.DataAccess
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.core.PrivilegeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Probes and owns the optional privilege backends. It intentionally does not request
 * anything during app start: root/Shizuku/Dhizuku prompts must be user initiated.
 */
object PrivilegeManager {
    private const val PREFS = "privilege"
    private const val KEY_MODE = "selected_mode"
    private const val REQUEST_CODE = 0x0DA7A

    private lateinit var appContext: Context
    private val runners = ConcurrentHashMap<PrivilegeMode, ShellRunner>()

    private val _selectedMode = MutableStateFlow(PrivilegeMode.ROOT)
    val selectedMode: StateFlow<PrivilegeMode> = _selectedMode.asStateFlow()

    private val _status = MutableStateFlow(
        PrivilegeStatus(
            mode = PrivilegeMode.ROOT,
            available = false,
            permissionGranted = false,
            title = "未检测",
            detail = "点击刷新以检测权限",
        ),
    )
    val status: StateFlow<PrivilegeStatus> = _status.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        val saved = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, PrivilegeMode.ROOT.name)
        val mode = runCatching { PrivilegeMode.valueOf(saved ?: "ROOT") }.getOrDefault(PrivilegeMode.ROOT)
        _selectedMode.value = mode
        Log.i("DataArkPrivilege", "init selected=$mode saved=$saved")
    }

    fun setSelectedMode(mode: PrivilegeMode) {
        _selectedMode.value = mode
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODE, mode.name).apply()
    }

    fun runner(mode: PrivilegeMode = _selectedMode.value): ShellRunner? = when (mode) {
        PrivilegeMode.ROOT -> runners.getOrPut(mode) { RootShellRunner() }
        PrivilegeMode.SHIZUKU -> runners.getOrPut(mode) { ShizukuShellRunner() }
        PrivilegeMode.DHIZUKU -> runners.getOrPut(mode) { DhizukuShellRunner(appContext) }
        PrivilegeMode.NONE -> null
    }

    suspend fun refresh(): PrivilegeStatus {
        val status = probe(_selectedMode.value)
        Log.i("DataArkPrivilege", "refresh selected=${_selectedMode.value} status=${status.title} access=${status.access}")
        return status
    }

    suspend fun probe(mode: PrivilegeMode): PrivilegeStatus {
        val status = when (mode) {
            PrivilegeMode.ROOT -> probeRoot()
            PrivilegeMode.SHIZUKU -> probeShizuku()
            PrivilegeMode.DHIZUKU -> probeDhizuku()
            PrivilegeMode.NONE -> PrivilegeStatus(
                mode = PrivilegeMode.NONE,
                available = false,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "无特权",
                detail = "只能浏览应用列表，无法读取应用私有数据",
            )
        }
        if (mode == _selectedMode.value) _status.value = status
        return status
    }

    suspend fun requestPermission(mode: PrivilegeMode = _selectedMode.value): PrivilegeStatus {
        when (mode) {
            PrivilegeMode.ROOT -> withContext(Dispatchers.IO) {
                runCatching { Shell.getShell() }
            }
            PrivilegeMode.SHIZUKU -> requestShizukuPermission()
            PrivilegeMode.DHIZUKU -> requestDhizukuPermission()
            PrivilegeMode.NONE -> Unit
        }
        return probe(mode)
    }

    fun canUsePrivateData(app: AppEntry, status: PrivilegeStatus = _status.value): Boolean {
        return when (status.access) {
            DataAccess.FULL -> true
            DataAccess.DEBUGGABLE_ONLY -> app.isDebuggable
            DataAccess.APK_ONLY, DataAccess.NONE -> false
        }
    }

    fun accessLabel(access: DataAccess): String = when (access) {
        DataAccess.FULL -> "完整应用数据"
        DataAccess.DEBUGGABLE_ONLY -> "仅 debuggable 应用私有数据 + APK"
        DataAccess.APK_ONLY -> "仅 APK / 应用列表"
        DataAccess.NONE -> "无数据访问"
    }

    fun instructions(mode: PrivilegeMode): String = when (mode) {
        PrivilegeMode.ROOT -> """
            1. 确保设备已用 Magisk、KernelSU 或 APatch 获取 Root。
            2. 回到本应用点击「授权 Root」，在 Root 管理器中允许。
            3. Root 模式可读写任意应用的 /data/user/0 与 /data/user_de 数据。

            提示：Root 是唯一能完整备份未开放调试的应用私有数据的模式。
        """.trimIndent()
        PrivilegeMode.SHIZUKU -> """
            1. 安装 Shizuku 应用，并通过无线调试或 ADB 启动服务：
               adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh
            2. 在 Shizuku 中授权「数据方舟」。
            3. Shizuku 以 ADB 模式运行时 UID 为 2000，仅能读取：
               • 任意应用 APK
               • debuggable 应用的私有数据（通过 run-as）
               注意：Android 11+ 的 FUSE 可能限制 shell 读取其他应用的
               Android/data、obb；本应用仅在完整数据权限下备份外部数据。
            4. 如果 Shizuku 以 Root 模式启动（UID 0），则拥有完整数据权限。
        """.trimIndent()
        PrivilegeMode.DHIZUKU -> """
            1. 安装 Dhizuku，并将其设为 Device Owner：
               adb shell dpm set-device-owner com.rosan.dhizuku/.server.DhizukuDAReceiver
            2. 在 Dhizuku 中授权「数据方舟」。
            3. Dhizuku 提供 Device Owner 能力，但不提供跨应用私有目录读取。
               本应用在 Dhizuku 模式下支持 APK 备份/应用管理，私有数据仍需要 Root
               或 Root 模式 Shizuku。
        """.trimIndent()
        PrivilegeMode.NONE -> "未选择特权模式。"
    }

    private suspend fun probeRoot(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val granted = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        if (granted != true) {
            return@withContext PrivilegeStatus(
                mode = PrivilegeMode.ROOT,
                available = true,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Root 未授权",
                detail = "Root 管理器尚未授予本应用 su 权限",
            )
        }
        runCatching {
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                PrivilegeStatus(
                    mode = PrivilegeMode.ROOT,
                    available = true,
                    permissionGranted = false,
                    access = DataAccess.NONE,
                    title = "Root 不可用",
                    detail = "su 返回了非 root shell",
                )
            } else {
                PrivilegeStatus(
                    mode = PrivilegeMode.ROOT,
                    available = true,
                    permissionGranted = true,
                    uid = 0,
                    access = DataAccess.FULL,
                    title = "Root · UID 0",
                    detail = "可完整读写应用私有数据（/data/user/0、/data/user_de）",
                )
            }
        }.getOrElse { throwable ->
            PrivilegeStatus(
                mode = PrivilegeMode.ROOT,
                available = true,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Root 检测失败",
                detail = throwable.message ?: "未知错误",
                error = throwable.message,
            )
        }
    }

    private suspend fun probeShizuku(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return@withContext PrivilegeStatus(
                mode = PrivilegeMode.SHIZUKU,
                available = false,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Shizuku 未运行",
                detail = "请先启动 Shizuku 服务（无线调试或 ADB）",
            )
        }
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!granted) {
            return@withContext PrivilegeStatus(
                mode = PrivilegeMode.SHIZUKU,
                available = true,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Shizuku 已运行 · 未授权",
                detail = "点击授权，在 Shizuku 中允许本应用",
            )
        }
        runCatching {
            val uid = Shizuku.getUid()
            if (uid < 0) {
                return@withContext PrivilegeStatus(
                    mode = PrivilegeMode.SHIZUKU,
                    available = true,
                    permissionGranted = false,
                    access = DataAccess.NONE,
                    title = "Shizuku 权限已失效",
                    detail = "请在 Shizuku 应用中重新授权本应用",
                )
            }
            val full = uid == 0
            PrivilegeStatus(
                mode = PrivilegeMode.SHIZUKU,
                available = true,
                permissionGranted = true,
                uid = uid,
                access = if (full) DataAccess.FULL else DataAccess.DEBUGGABLE_ONLY,
                title = if (full) "Shizuku · Root 模式 (UID 0)" else "Shizuku · ADB 模式 (UID $uid)",
                detail = if (full) {
                    "Shizuku 服务以 root 运行，能力等同 Root 模式"
                } else {
                    "可备份 APK 与 debuggable 应用私有数据；外部数据及普通应用私有数据需 Root"
                },
            )
        }.getOrElse { throwable ->
            PrivilegeStatus(
                mode = PrivilegeMode.SHIZUKU,
                available = true,
                permissionGranted = true,
                access = DataAccess.NONE,
                title = "Shizuku 状态异常",
                detail = throwable.message ?: "未知错误",
                error = throwable.message,
            )
        }
    }

    private suspend fun probeDhizuku(): PrivilegeStatus = withContext(Dispatchers.IO) {
        val initialized = runCatching { Dhizuku.init(appContext) }.getOrDefault(false)
        if (!initialized) {
            return@withContext PrivilegeStatus(
                mode = PrivilegeMode.DHIZUKU,
                available = false,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Dhizuku 未激活",
                detail = "请安装 Dhizuku 并设为 Device Owner",
            )
        }
        val granted = runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)
        if (!granted) {
            return@withContext PrivilegeStatus(
                mode = PrivilegeMode.DHIZUKU,
                available = true,
                permissionGranted = false,
                access = DataAccess.NONE,
                title = "Dhizuku 已激活 · 未授权",
                detail = "点击授权，在 Dhizuku 中允许本应用",
            )
        }
        val uid = runCatching { DhizukuShellRunner(appContext).uid() }.getOrNull()
        PrivilegeStatus(
            mode = PrivilegeMode.DHIZUKU,
            available = true,
            permissionGranted = true,
            uid = uid,
            access = if (uid == 0) DataAccess.FULL else DataAccess.APK_ONLY,
            title = "Dhizuku · Device Owner",
            detail = if (uid == 0) {
                "Dhizuku 进程为 UID 0，拥有完整数据能力"
            } else {
                "Device Owner 不提供跨应用私有目录。当前模式仅支持 APK 备份；私有数据需 Root 或 Root 模式 Shizuku"
            },
        )
    }

    private suspend fun requestShizukuPermission() {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return
        if (runCatching { Shizuku.checkSelfPermission() }.getOrDefault(-1) == PackageManager.PERMISSION_GRANTED) return
        withTimeoutOrNull(90_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode == REQUEST_CODE) {
                            runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation {
                    runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                }
                runCatching { Shizuku.requestPermission(REQUEST_CODE) }
                    .onFailure {
                        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
                        if (cont.isActive) cont.cancel(it)
                    }
            }
        }
    }

    private suspend fun requestDhizukuPermission() {
        if (!runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)) {
            withTimeoutOrNull(90_000) {
                suspendCancellableCoroutine<Unit> { cont ->
                    val listener = object : DhizukuRequestPermissionListener() {
                        override fun onRequestPermission(grantResult: Int) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    runCatching { Dhizuku.requestPermission(listener) }
                        .onFailure {
                            if (cont.isActive) cont.cancel(it)
                        }
                }
            }
        }
    }

    fun androidVersion(): String = Build.VERSION.RELEASE ?: "?"
}
