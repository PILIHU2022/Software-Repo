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
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

class BackupRepository(private val context: Context) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    fun storageInfo(): StorageInfo = StorageManager.storageInfo(context)

    fun rootDir(): File = storageInfo().root

    fun listBackups(): List<BackupRecord> {
        val root = rootDir()
        if (!root.isDirectory) return emptyList()
        val records = mutableListOf<BackupRecord>()
        root.listFiles()?.filter { it.isDirectory }?.forEach { packageDir ->
            packageDir.listFiles()?.filter { it.isDirectory }?.forEach { snapshotDir ->
                val meta = readMeta(snapshotDir) ?: return@forEach
                records += BackupRecord(
                    packageName = meta.packageName,
                    createdAt = meta.createdAt,
                    dir = snapshotDir,
                    meta = meta,
                )
            }
        }
        return records.sortedByDescending { it.createdAt }
    }

    fun find(id: String): BackupRecord? = listBackups().firstOrNull { it.id == id }

    fun readMeta(dir: File): BackupMeta? {
        val file = File(dir, "meta.json")
        if (!file.isFile) return null
        return runCatching { gson.fromJson(file.readText(), BackupMeta::class.java) }.getOrNull()
    }

    fun writeMeta(dir: File, meta: BackupMeta) {
        File(dir, "meta.json").writeText(gson.toJson(meta))
    }

    fun newSnapshotDir(packageName: String, createdAt: Long): File {
        val parent = File(rootDir(), safeComponent(packageName))
        parent.mkdirs()
        val child = File(parent, snapshotName(createdAt))
        child.mkdirs()
        return child
    }

    fun delete(record: BackupRecord): Boolean = runCatching {
        record.dir.deleteRecursively()
    }.getOrDefault(false)

    fun verify(record: BackupRecord): VerifyResult {
        val meta = record.meta
        if (meta.dataArchive != null) {
            val file = File(record.dir, meta.dataArchive)
            if (!file.isFile) return VerifyResult(VerifyState.MISSING, "内部数据归档缺失")
            val digest = runCatching { Checksum.sha256(file) }.getOrNull()
                ?: return VerifyResult(VerifyState.MISMATCH, "无法读取内部数据归档")
            if (meta.dataArchiveSha256 != null && digest != meta.dataArchiveSha256) {
                return VerifyResult(VerifyState.MISMATCH, "内部数据 SHA-256 不匹配")
            }
        }
        if (meta.externalArchive != null) {
            val file = File(record.dir, meta.externalArchive)
            if (!file.isFile) return VerifyResult(VerifyState.MISSING, "外部数据归档缺失")
            val digest = runCatching { Checksum.sha256(file) }.getOrNull()
                ?: return VerifyResult(VerifyState.MISMATCH, "无法读取外部数据归档")
            if (meta.externalArchiveSha256 != null && digest != meta.externalArchiveSha256) {
                return VerifyResult(VerifyState.MISMATCH, "外部数据 SHA-256 不匹配")
            }
        }
        return VerifyResult(VerifyState.OK, "校验通过")
    }

    fun archiveFile(record: BackupRecord, name: String?): File? =
        name?.let { File(record.dir, it) }?.takeIf { it.isFile }

    fun totalBytes(): Long = listBackups().sumOf { it.sizeBytes }

    fun snapshotName(createdAt: Long): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        return format.format(java.util.Date(createdAt))
    }

    private fun safeComponent(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
}
