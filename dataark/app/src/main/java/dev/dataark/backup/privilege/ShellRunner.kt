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

import dev.dataark.backup.core.PrivilegeMode

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
) {
    val ok: Boolean get() = code == 0
    val combined: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(stderr.trim())
            }
        }

    fun requireSuccess(action: String) {
        if (!ok) {
            val detail = combined.take(2_000).ifBlank { "exit code $code" }
            throw ShellCommandException("$action 失败：$detail")
        }
    }
}

class ShellCommandException(message: String, cause: Throwable? = null) : Exception(message, cause)

interface ShellRunner {
    val mode: PrivilegeMode

    /** Runs a command as a single sh script. A timeout of 0 means no timeout. */
    suspend fun run(command: String, timeoutMs: Long = 0): ShellResult

    suspend fun uid(): Int?
}
