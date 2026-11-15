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

package dev.dataark.backup.debug

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.util.Log
import dev.dataark.backup.core.BackupOptions
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.service.BackupService
import kotlinx.coroutines.runBlocking

/**
 * Debug-only launcher used by adb integration tests. An activity is used instead of a
 * broadcast receiver because some OEM background policies drop implicit/explicit broadcasts
 * sent from the shell. It is merged only into debug builds.
 */
class DebugCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "DataArk debug command" })
        handle(intent)
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 250)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        val op = intent.getStringExtra("op")
        Log.i(TAG, "debug op=$op packages=${intent.getStringExtra("packages")}")
        when (op) {
            "mode" -> {
                val mode = runCatching {
                    PrivilegeMode.valueOf(intent.getStringExtra("mode").orEmpty())
                }.getOrNull() ?: return
                PrivilegeManager.init(this)
                PrivilegeManager.setSelectedMode(mode)
            }
            "backup" -> {
                val packages = intent.getStringExtra("packages").orEmpty()
                    .split(',').map(String::trim).filter(String::isNotBlank)
                if (packages.isEmpty()) return
                BackupService.startBackup(
                    this,
                    packages,
                    BackupOptions(
                        includeInternalData = intent.getBooleanExtra("internal", true),
                        includeDeviceProtectedData = intent.getBooleanExtra("de", true),
                        includeExternalData = intent.getBooleanExtra("external", true),
                        includeApk = intent.getBooleanExtra("apk", true),
                        compress = intent.getBooleanExtra("compress", false),
                    ),
                )
            }
            "restore" -> {
                val id = intent.getStringExtra("id").orEmpty()
                if (id.isNotBlank()) BackupService.startRestore(this, id)
            }
            "request" -> {
                val mode = runCatching {
                    PrivilegeMode.valueOf(intent.getStringExtra("mode").orEmpty())
                }.getOrNull() ?: PrivilegeMode.SHIZUKU
                Thread {
                    runCatching { runBlocking { PrivilegeManager.requestPermission(mode) } }
                        .onFailure { Log.e(TAG, "request failed", it) }
                }.start()
            }
        }
    }

    private companion object {
        const val TAG = "DataArkDebug"
    }
}
