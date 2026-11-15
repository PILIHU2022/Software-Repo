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

package dev.dataark.backup.core

import java.io.File

/** The privilege source currently used to perform privileged operations. */
enum class PrivilegeMode(val label: String, val shortLabel: String) {
    ROOT("Root / su", "Root"),
    SHIZUKU("Shizuku", "Shizuku"),
    DHIZUKU("Dhizuku", "Dhizuku"),
    NONE("无特权", "无"),
}

/**
 * How much of another app's private storage this privilege source can read.
 *
 * FULL: any package's /data/user/0 and /data/user_de data.
 * DEBUGGABLE_ONLY: data only for packages marked android:debuggable (via run-as).
 * APK_ONLY: app list + APK extraction only, no private data.
 * NONE: no privileged access at all.
 */
enum class DataAccess {
    FULL,
    DEBUGGABLE_ONLY,
    APK_ONLY,
    NONE,
}

data class PrivilegeStatus(
    val mode: PrivilegeMode,
    val available: Boolean,
    val permissionGranted: Boolean,
    val uid: Int? = null,
    val access: DataAccess = DataAccess.NONE,
    val title: String = "",
    val detail: String = "",
    val error: String? = null,
) {
    val isUsable: Boolean get() = available && permissionGranted
    val canReadAnyPrivateData: Boolean get() = access == DataAccess.FULL
}

data class StorageInfo(
    val root: File,
    val path: String,
    val persistent: Boolean,
    val allFilesAccess: Boolean,
    val writable: Boolean,
)

data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isDebuggable: Boolean,
    val enabled: Boolean,
    val minSdk: Int,
    val targetSdk: Int,
    val installedAt: Long,
    val updatedAt: Long,
    val sourceDir: String?,
    val splitSourceDirs: List<String>,
    val dataDir: String?,
    val deviceProtectedDataDir: String?,
) {
    val apkPaths: List<String>
        get() = (listOfNotNull(sourceDir) + splitSourceDirs).distinct()
}

data class BackupOptions(
    val includeInternalData: Boolean = true,
    val includeDeviceProtectedData: Boolean = true,
    val includeExternalData: Boolean = true,
    val includeApk: Boolean = true,
    val compress: Boolean = false,
)

data class BackupMeta(
    val schema: Int = 1,
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val uid: Int,
    val isDebuggable: Boolean = false,
    val minSdk: Int = 0,
    val targetSdk: Int = 0,
    val createdAt: Long,
    val privilegeMode: String,
    val androidRelease: String,
    val deviceModel: String,
    val backupRoot: String,
    val hasInternalData: Boolean = false,
    val hasDeviceProtectedData: Boolean = false,
    val hasExternalData: Boolean = false,
    val hasApk: Boolean = false,
    val dataArchive: String? = null,
    val dataArchiveSha256: String? = null,
    val dataArchiveBytes: Long = 0,
    val externalArchive: String? = null,
    val externalArchiveSha256: String? = null,
    val externalArchiveBytes: Long = 0,
    val apkFiles: List<String> = emptyList(),
    val apkBytes: Long = 0,
    val compression: String = "none",
    val tarSelinux: Boolean = false,
    val note: String? = null,
) {
    val totalBytes: Long get() = dataArchiveBytes + externalArchiveBytes + apkBytes
}

data class BackupRecord(
    val packageName: String,
    val createdAt: Long,
    val dir: File,
    val meta: BackupMeta,
) {
    val id: String get() = "$packageName/${dir.name}"
    val label: String get() = meta.label
    val mode: String get() = meta.privilegeMode
    val sizeBytes: Long get() = meta.totalBytes
}

enum class VerifyState { UNKNOWN, OK, MISMATCH, MISSING }

data class VerifyResult(
    val state: VerifyState,
    val message: String,
)

enum class BackupScope(val label: String) {
    ALL("全部应用"),
    USER("用户应用"),
    SYSTEM("系统应用"),
}

data class OperationResultItem(
    val packageName: String,
    val label: String,
    val success: Boolean,
    val skipped: Boolean = false,
    val message: String = "",
    val bytes: Long = 0,
    val backupDir: String? = null,
)

data class OperationState(
    val kind: OperationKind? = null,
    val active: Boolean = false,
    val finished: Boolean = false,
    val cancelled: Boolean = false,
    val total: Int = 0,
    val completed: Int = 0,
    val currentPackage: String = "",
    val currentLabel: String = "",
    val phase: String = "",
    val detail: String = "",
    val results: List<OperationResultItem> = emptyList(),
    val fatalError: String? = null,
    val startedAt: Long = 0,
    val finishedAt: Long = 0,
) {
    val progress: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    val successCount: Int get() = results.count { it.success && !it.skipped }
    val failureCount: Int get() = results.count { !it.success && !it.skipped }
}

enum class OperationKind(val label: String) {
    BACKUP("备份"),
    RESTORE("恢复"),
}
