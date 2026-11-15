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
import com.rosan.dhizuku.api.Dhizuku
import dev.dataark.backup.core.PrivilegeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit

class DhizukuShellRunner(private val appContext: Context) : ShellRunner {
    override val mode: PrivilegeMode = PrivilegeMode.DHIZUKU

    override suspend fun run(command: String, timeoutMs: Long): ShellResult = withContext(Dispatchers.IO) {
        if (!runCatching { Dhizuku.init(appContext) }.getOrDefault(false)) {
            throw ShellCommandException("Dhizuku 未初始化（服务未安装或未激活）")
        }
        if (!runCatching { Dhizuku.isPermissionGranted() }.getOrDefault(false)) {
            throw ShellCommandException("Dhizuku 权限未授予")
        }
        val process = try {
            Dhizuku.newProcess(arrayOf("sh", "-c", command), null, null)
        } catch (e: Exception) {
            throw ShellCommandException("Dhizuku 执行命令失败：${e.message}", e)
        }
        coroutineScope {
            val stdout = async(Dispatchers.IO) { readAll(process.inputStream) }
            val stderr = async(Dispatchers.IO) { readAll(process.errorStream) }
            val finished = try {
                if (timeoutMs > 0) {
                    process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                } else {
                    process.waitFor()
                    true
                }
            } catch (e: Exception) {
                runCatching { process.destroy() }
                throw ShellCommandException("Dhizuku 命令超时或被取消", e)
            }
            if (!finished) {
                runCatching { process.destroy() }
                throw ShellCommandException("Dhizuku 命令超时")
            }
            ShellResult(process.exitValue(), stdout.await(), stderr.await())
        }
    }

    private fun readAll(input: InputStream): String = input.use { it.readBytes().toString(Charsets.UTF_8) }

    override suspend fun uid(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            run("id -u", 15_000).stdout.trim().toIntOrNull()
        }.getOrNull()
    }
}
