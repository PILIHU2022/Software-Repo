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

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.core.content.ContextCompat
import java.io.File

object StorageManager {
    private const val PERSISTENT_DIR = "DataArk"

    fun storageInfo(context: Context): StorageInfo {
        val allFiles = hasAllFilesAccess(context)
        val external = Environment.getExternalStorageDirectory()
        val persistent = File(external, PERSISTENT_DIR)
        val fallback = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            PERSISTENT_DIR,
        )

        fun isWritable(dir: File): Boolean {
            if (!dir.exists() && !dir.mkdirs()) return false
            return runCatching { File(dir, ".write-test").apply { writeText("ok") }.delete() }.isSuccess
        }

        val persistentWritable = allFiles && isWritable(persistent)
        val chosen = if (persistentWritable) persistent else fallback
        val writable = isWritable(chosen)
        return StorageInfo(
            root = chosen,
            path = chosen.absolutePath,
            persistent = chosen == persistent,
            allFilesAccess = allFiles,
            writable = writable,
        )
    }

    fun hasAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            // Some test/OEM images do not reflect the appop in Environment; query it directly.
            return runCatching {
                val appOps = context.getSystemService(AppOpsManager::class.java)
                appOps?.unsafeCheckOpNoThrow(
                    "android:manage_external_storage",
                    Process.myUid(),
                    context.packageName,
                ) == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun backupRoot(context: Context): File = storageInfo(context).root
}
