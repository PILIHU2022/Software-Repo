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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

class AppRepository(private val context: Context) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun load(scope: BackupScope): List<AppEntry> = withContext(Dispatchers.IO) {
        val packages = installedPackages()
        val collator = Collator.getInstance(Locale.CHINA)
        packages.asSequence()
            .mapNotNull { it.toEntry() }
            .filter { entry ->
                when (scope) {
                    BackupScope.ALL -> true
                    BackupScope.USER -> !entry.isSystem
                    BackupScope.SYSTEM -> entry.isSystem
                }
            }
            .sortedWith { a, b -> collator.compare(a.label, b.label) }
            .toList()
    }

    suspend fun find(packageName: String): AppEntry? = withContext(Dispatchers.IO) {
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            info.toEntry()
        }.getOrNull()
    }

    private fun installedPackages(): List<PackageInfo> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())
    }

    private fun PackageInfo.toEntry(): AppEntry? {
        val ai = applicationInfo ?: return null
        val label = runCatching { pm.getApplicationLabel(ai).toString() }
            .getOrDefault(packageName)
        val flags = ai.flags
        return AppEntry(
            packageName = packageName,
            label = label.ifBlank { packageName },
            uid = ai.uid,
            versionName = versionName ?: "",
            versionCode = longVersionCodeCompat(this),
            isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            isDebuggable = (flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            enabled = ai.enabled,
            minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ai.minSdkVersion else 0,
            targetSdk = ai.targetSdkVersion,
            installedAt = firstInstallTime,
            updatedAt = lastUpdateTime,
            sourceDir = ai.sourceDir,
            splitSourceDirs = ai.splitSourceDirs?.toList().orEmpty(),
            dataDir = ai.dataDir,
            deviceProtectedDataDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ai.deviceProtectedDataDir
            } else {
                null
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun longVersionCodeCompat(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }
}
