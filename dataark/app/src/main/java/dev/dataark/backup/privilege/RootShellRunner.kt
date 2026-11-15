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

import com.topjohnwu.superuser.Shell
import dev.dataark.backup.core.PrivilegeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class RootShellRunner : ShellRunner {
    override val mode: PrivilegeMode = PrivilegeMode.ROOT

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        val shell = try {
            Shell.getShell()
        } catch (e: Exception) {
            throw ShellCommandException("无法创建 Root shell：${e.message}", e)
        }
        if (!shell.isRoot) {
            throw ShellCommandException("Root 权限不可用：su 返回了非 root shell")
        }
        val future = Shell.cmd(command).enqueue()
        val result = try {
            if (timeoutMs > 0) future.get(timeoutMs, TimeUnit.MILLISECONDS) else future.get()
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw ShellCommandException("命令执行超时", e)
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw ShellCommandException("命令被取消", e)
        }
        ShellResult(
            code = result.code,
            stdout = result.out.joinToString("\n"),
            stderr = result.err.joinToString("\n"),
        )
    }

    override suspend fun uid(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val shell = Shell.getShell()
            if (shell.isRoot) 0 else null
        }.getOrNull()
    }
}
