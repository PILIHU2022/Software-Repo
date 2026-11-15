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

import dev.dataark.backup.privilege.ShellRunner

data class TarSupport(
    val selinux: Boolean = false,
    val numericOwner: Boolean = true,
    val noSameOwner: Boolean = true,
    val gzip: Boolean = true,
    val exclude: Boolean = true,
)

object ShellScripts {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun pathJoin(vararg parts: String): String = parts.joinToString("/")

    suspend fun probeTar(runner: ShellRunner): TarSupport {
        val help = runCatching {
            runner.run("tar --help 2>&1", 15_000).combined
        }.getOrDefault("")
        return TarSupport(
            selinux = help.contains("--selinux"),
            numericOwner = help.contains("--numeric-owner"),
            noSameOwner = help.contains("--no-same-owner"),
            gzip = help.contains("gzip") || help.contains("-z"),
            exclude = help.contains("--exclude"),
        )
    }

    fun tarCreateFlags(tar: TarSupport, selinux: Boolean, compress: Boolean): String = buildString {
        if (tar.numericOwner) append("--numeric-owner ")
        if (selinux && tar.selinux) append("--selinux ")
        append(if (compress) "-czpf" else "-cpf")
    }

    fun tarExtractFlags(tar: TarSupport, selinux: Boolean, compress: Boolean): String = buildString {
        if (tar.numericOwner) append("--numeric-owner ")
        if (selinux && tar.selinux) append("--selinux ")
        append(if (compress) "-xzpf" else "-xpf")
    }
}
