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

import android.os.ParcelFileDescriptor
import dev.dataark.backup.core.PrivilegeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.InputStream

class ShizukuShellRunner : ShellRunner {
    override val mode: PrivilegeMode = PrivilegeMode.SHIZUKU

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        // Shizuku 13.1.x removed the public Shizuku.newProcess() helper; the server still
        // implements IShizukuService.newProcess and enforces its own permission. This is a
        // guarded compatibility path: if a future Shizuku release removes it, fall back to
        // the official UserService API.
        val uid = runCatching { Shizuku.getUid() }.getOrDefault(-1)
        if (uid < 0) throw ShellCommandException("Shizuku 权限未授予 (uid=-1)")
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
            ?: throw ShellCommandException("Shizuku binder 不存在")
        val service = IShizukuService.Stub.asInterface(binder)
            ?: throw ShellCommandException("无法连接 Shizuku 服务")
        val process = try {
            service.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Exception) {
            throw ShellCommandException("Shizuku 执行命令失败：${e.message}", e)
        }
        executeRemote(process, timeoutMs)
    }

    private suspend fun executeRemote(process: IRemoteProcess, timeoutMs: Long): ShellResult = coroutineScope {
        val stdout = async(Dispatchers.IO) { readAll(process.inputStream) }
        val stderr = async(Dispatchers.IO) { readAll(process.errorStream) }
        val code = try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { withContext(Dispatchers.IO) { process.waitFor() } }
            } else {
                withContext(Dispatchers.IO) { process.waitFor() }
            }
        } catch (e: Exception) {
            runCatching { process.destroy() }
            throw ShellCommandException("Shizuku 命令超时或被取消", e)
        }
        ShellResult(code, stdout.await(), stderr.await())
    }

    private fun readAll(fd: ParcelFileDescriptor): String {
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { input: InputStream ->
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    override suspend fun uid(): Int? = withContext(Dispatchers.IO) {
        runCatching { Shizuku.getUid() }.getOrNull()
    }
}
