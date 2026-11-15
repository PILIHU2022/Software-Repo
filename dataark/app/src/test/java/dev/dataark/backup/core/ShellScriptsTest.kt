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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellScriptsTest {
    @Test
    fun quoteEscapesSingleQuotes() {
        assertEquals("'abc'", ShellScripts.quote("abc"))
        assertEquals("'a'\\''b'", ShellScripts.quote("a'b"))
        assertEquals("'/data/user/0/com.example'", ShellScripts.quote("/data/user/0/com.example"))
    }

    @Test
    fun createFlagsIncludePrivilegedOptionsOnlyWhenSupported() {
        val support = TarSupport(selinux = true, numericOwner = true, noSameOwner = true)
        val flags = ShellScripts.tarCreateFlags(support, selinux = true, compress = false)
        assertTrue(flags.contains("--numeric-owner"))
        assertTrue(flags.contains("--selinux"))
        assertTrue(flags.contains("-cpf"))

        val legacy = TarSupport(selinux = false, numericOwner = false)
        val legacyFlags = ShellScripts.tarCreateFlags(legacy, selinux = true, compress = true)
        assertFalse(legacyFlags.contains("--selinux"))
        assertFalse(legacyFlags.contains("--numeric-owner"))
        assertTrue(legacyFlags.contains("-czpf"))
    }

    @Test
    fun extractFlagsUseGzipWhenNeeded() {
        val support = TarSupport(selinux = true, numericOwner = true)
        val flags = ShellScripts.tarExtractFlags(support, selinux = false, compress = true)
        assertTrue(flags.contains("-xzpf"))
        assertFalse(flags.contains("--selinux"))
    }

    @Test
    fun progressIsClamped() {
        val idle = OperationState()
        assertEquals(0f, idle.progress, 0.0001f)
        val half = OperationState(total = 4, completed = 2)
        assertEquals(0.5f, half.progress, 0.0001f)
        val over = OperationState(total = 2, completed = 5)
        assertEquals(1f, over.progress, 0.0001f)
    }

    @Test
    fun metaTotalBytesAddsArchives() {
        val meta = BackupMeta(
            packageName = "a.b",
            label = "AB",
            versionName = "1",
            versionCode = 1,
            uid = 10000,
            createdAt = 0,
            privilegeMode = "ROOT",
            androidRelease = "14",
            deviceModel = "test",
            backupRoot = "/tmp",
            dataArchiveBytes = 10,
            externalArchiveBytes = 20,
        )
        assertEquals(30, meta.totalBytes)
    }
}
