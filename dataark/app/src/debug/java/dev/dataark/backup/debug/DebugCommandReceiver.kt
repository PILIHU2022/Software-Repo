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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.dataark.backup.core.BackupOptions
import dev.dataark.backup.core.PrivilegeMode
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.service.BackupService
import kotlinx.coroutines.runBlocking

/**
 * Debug-only ADB hook. It is merged only into debug builds (src/debug manifest) so release
 * builds never expose it. Used to drive end-to-end backup/restore tests without fragile UI taps.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val op = intent.getStringExtra("op")
        Log.i(TAG, "debug command op=$op packages=${intent.getStringExtra("packages")}")
        when (op) {
            "mode" -> {
                val mode = runCatching {
                    PrivilegeMode.valueOf(intent.getStringExtra("mode").orEmpty())
                }.getOrNull() ?: return
                PrivilegeManager.init(context)
                PrivilegeManager.setSelectedMode(mode)
            }
            "backup" -> {
                val packages = intent.getStringExtra("packages").orEmpty()
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                if (packages.isEmpty()) return
                BackupService.startBackup(
                    context,
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
                if (id.isNotBlank()) BackupService.startRestore(context, id)
            }
            "shell" -> {
                // Diagnostic only: run a command through the currently selected privilege
                // backend and log the result. Used to verify what each backend can really read.
                val command = intent.getStringExtra("cmd").orEmpty()
                if (command.isBlank()) return
                val mode = PrivilegeManager.selectedMode.value
                Thread {
                    val runner = PrivilegeManager.runner(mode)
                    if (runner == null) {
                        Log.i(TAG, "shell mode=$mode no runner")
                        return@Thread
                    }
                    val result = runCatching { runBlocking { runner.run(command, 60_000) } }
                    result.onSuccess { r ->
                        Log.i(TAG, "shell mode=$mode code=${r.code} out=<<<${r.stdout}>>> err=<<<${r.stderr}>>>")
                    }.onFailure { t ->
                        Log.i(TAG, "shell mode=$mode failed=${t.message}")
                    }
                }.start()
            }
        }
    }

    companion object {
        private const val ACTION = "dev.dataark.backup.DEBUG_COMMAND"
        private const val TAG = "DataArkDebug"
    }
}
