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
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import dev.dataark.backup.privilege.PrivilegeManager
import dev.dataark.backup.privilege.ShellResult
import dev.dataark.backup.privilege.ShellRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class OperationCancelledException : CancellationException("操作已取消")

data class EngineProgress(
    val packageName: String = "",
    val label: String = "",
    val phase: String = "",
    val detail: String = "",
    val completed: Int = 0,
    val total: Int = 0,
    val result: OperationResultItem? = null,
)

class BackupEngine(
    private val context: Context,
    private val appRepository: AppRepository,
    private val backupRepository: BackupRepository,
) {
    private val pm: PackageManager get() = context.packageManager

    suspend fun backup(
        apps: List<AppEntry>,
        options: BackupOptions,
        onProgress: (EngineProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): List<OperationResultItem> = withContext(Dispatchers.IO) {
        val mode = PrivilegeManager.selectedMode.value
        val status = PrivilegeManager.status.value
        val runner = PrivilegeManager.runner(mode)
        val tar = runner?.let { ShellScripts.probeTar(it) } ?: TarSupport()
        val results = mutableListOf<OperationResultItem>()
        Log.i(TAG, "backup start mode=$mode access=${status.access} apps=${apps.size}")

        apps.forEachIndexed { index, app ->
            if (isCancelled()) throw OperationCancelledException()
            onProgress(
                EngineProgress(
                    packageName = app.packageName,
                    label = app.label,
                    phase = "准备",
                    detail = "检查应用数据目录",
                    completed = index,
                    total = apps.size,
                ),
            )
            val result = backupOne(app, options, mode, status, runner, tar) { phase, detail ->
                onProgress(
                    EngineProgress(
                        packageName = app.packageName,
                        label = app.label,
                        phase = phase,
                        detail = detail,
                        completed = index,
                        total = apps.size,
                    ),
                )
            }
            results += result
            Log.i(TAG, "backup result ${app.packageName}: success=${result.success} skipped=${result.skipped} ${result.message}")
            onProgress(
                EngineProgress(
                    packageName = app.packageName,
                    label = app.label,
                    phase = if (result.success) "完成" else "失败",
                    detail = result.message,
                    completed = index + 1,
                    total = apps.size,
                    result = result,
                ),
            )
        }
        results
    }

    private suspend fun backupOne(
        app: AppEntry,
        options: BackupOptions,
        mode: PrivilegeMode,
        status: PrivilegeStatus,
        runner: ShellRunner?,
        tar: TarSupport,
        report: (String, String) -> Unit,
    ): OperationResultItem {
        val createdAt = System.currentTimeMillis()
        val snapshotDir = backupRepository.newSnapshotDir(app.packageName, createdAt)
        val wantsInternal = options.includeInternalData
        val canInternal = wantsInternal && PrivilegeManager.canUsePrivateData(app, status)
        val canExternal = options.includeExternalData &&
            status.access == DataAccess.FULL &&
            runner != null
        val wantsApk = options.includeApk

        if (!canInternal && !canExternal && !wantsApk) {
            snapshotDir.deleteRecursively()
            return OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = false,
                skipped = true,
                message = "当前权限模式无法读取该应用的私有数据，且未选择 APK/外部数据",
            )
        }

        var dataArchiveName: String? = null
        var dataBytes = 0L
        var dataSha: String? = null
        var hasDe = false
        var externalArchiveName: String? = null
        var externalBytes = 0L
        var externalSha: String? = null
        var apkFiles = emptyList<String>()
        var apkBytes = 0L
        val notes = mutableListOf<String>()

        try {
            if (wantsInternal && !canInternal) {
                notes += if (status.access == DataAccess.DEBUGGABLE_ONLY) {
                    "该应用未开启 debuggable，Shizuku ADB 模式跳过私有数据"
                } else {
                    "当前权限模式不支持读取私有数据"
                }
            }

            if (canInternal) {
                val archiveExt = if (options.compress) ".tar.gz" else ".tar"
                val dataFile = File(snapshotDir, "data$archiveExt")
                when {
                    status.access == DataAccess.FULL && runner != null -> {
                        report("读取私有数据", "停止应用并归档 /data/user/0、/data/user_de")
                        val script = rootBackupScript(
                            pkg = app.packageName,
                            dest = snapshotDir,
                            dataFile = dataFile,
                            externalFile = null,
                            includeData = true,
                            includeDe = options.includeDeviceProtectedData,
                            includeExternal = false,
                            tar = tar,
                            compress = options.compress,
                        )
                        val out = runner.run(script, 0).requireOk("备份 ${app.packageName} 私有数据")
                        hasDe = out.contains("DE=1")
                        if (dataFile.isFile && dataFile.length() > 0) {
                            dataArchiveName = dataFile.name
                            dataBytes = dataFile.length()
                            dataSha = Checksum.sha256(dataFile)
                        } else {
                            notes += "应用没有可读取的私有数据目录"
                        }
                    }
                    status.access == DataAccess.DEBUGGABLE_ONLY && app.isDebuggable && runner != null -> {
                        report("读取私有数据", "通过 run-as 归档 debuggable 应用数据")
                        val script = runAsBackupScript(
                            pkg = app.packageName,
                            dest = snapshotDir,
                            dataFile = dataFile,
                            tar = tar,
                            compress = options.compress,
                        )
                        runner.run(script, 0).requireOk("run-as 备份 ${app.packageName}")
                        if (dataFile.isFile && dataFile.length() > 0) {
                            dataArchiveName = dataFile.name
                            dataBytes = dataFile.length()
                            dataSha = Checksum.sha256(dataFile)
                        }
                        hasDe = false
                    }
                    else -> notes += "无可用私有数据权限"
                }
            }

            if (canExternal && runner != null) {
                val ext = if (options.compress) ".tar.gz" else ".tar"
                val extFile = File(snapshotDir, "external$ext")
                report("读取外部数据", "归档 Android/data 与 Android/obb")
                val script = shellExternalBackupScript(
                    pkg = app.packageName,
                    dest = snapshotDir,
                    externalFile = extFile,
                    tar = tar,
                    compress = options.compress,
                )
                val out = runner.run(script, 0).requireOk("备份 ${app.packageName} 外部数据")
                if (out.contains("EXT=1") && extFile.isFile && extFile.length() > 0) {
                    externalArchiveName = extFile.name
                    externalBytes = extFile.length()
                    externalSha = Checksum.sha256(extFile)
                } else {
                    notes += "应用没有外部数据目录（Android/data 或 obb）"
                }
            }

            if (wantsApk) {
                report("备份 APK", "复制 base.apk 与 split APK")
                val apkResult = copyApks(app, snapshotDir, runner, status)
                apkFiles = apkResult.first
                apkBytes = apkResult.second
                if (apkFiles.isEmpty()) notes += "未能复制 APK 文件"
            }

            if (dataArchiveName != null) {
                report("校验", "计算 SHA-256")
            }

            val meta = BackupMeta(
                packageName = app.packageName,
                label = app.label,
                versionName = app.versionName,
                versionCode = app.versionCode,
                uid = app.uid,
                isDebuggable = app.isDebuggable,
                minSdk = app.minSdk,
                targetSdk = app.targetSdk,
                createdAt = createdAt,
                privilegeMode = mode.name,
                androidRelease = Build.VERSION.RELEASE ?: "?",
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                backupRoot = backupRepository.rootDir().absolutePath,
                hasInternalData = dataArchiveName != null,
                hasDeviceProtectedData = hasDe,
                hasExternalData = externalArchiveName != null,
                hasApk = apkFiles.isNotEmpty(),
                dataArchive = dataArchiveName,
                dataArchiveSha256 = dataSha,
                dataArchiveBytes = dataBytes,
                externalArchive = externalArchiveName,
                externalArchiveSha256 = externalSha,
                externalArchiveBytes = externalBytes,
                apkFiles = apkFiles,
                apkBytes = apkBytes,
                compression = if (options.compress) "gzip" else "none",
                tarSelinux = tar.selinux && status.access == DataAccess.FULL,
                note = notes.takeIf { it.isNotEmpty() }?.joinToString("；"),
            )
            report("写入元数据", "生成 meta.json")
            backupRepository.writeMeta(snapshotDir, meta)

            val components = buildList {
                if (dataArchiveName != null) add("私有数据")
                if (externalArchiveName != null) add("外部数据")
                if (apkFiles.isNotEmpty()) add("${apkFiles.size} 个 APK")
            }
            val message = if (components.isEmpty()) {
                notes.joinToString("；").ifBlank { "没有可备份内容" }
            } else {
                "已备份：" + components.joinToString(" + ")
            }
            return OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = components.isNotEmpty(),
                skipped = components.isEmpty(),
                message = message,
                bytes = dataBytes + externalBytes + apkBytes,
                backupDir = snapshotDir.absolutePath,
            )
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: CancellationException) {
            snapshotDir.deleteRecursively()
            throw e
        } catch (e: Exception) {
            snapshotDir.deleteRecursively()
            return OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = false,
                message = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    suspend fun restore(
        record: BackupRecord,
        onProgress: (EngineProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): OperationResultItem = withContext(Dispatchers.IO) {
        if (isCancelled()) throw OperationCancelledException()
        val app = appRepository.find(record.packageName)
            ?: return@withContext OperationResultItem(
                packageName = record.packageName,
                label = record.meta.label,
                success = false,
                message = "应用未安装，无法恢复数据。请先安装同版本 APK。",
            )
        val mode = PrivilegeManager.selectedMode.value
        val status = PrivilegeManager.status.value
        val runner = PrivilegeManager.runner(mode)
        val tar = runner?.let { ShellScripts.probeTar(it) } ?: TarSupport()
        val dataFile = backupRepository.archiveFile(record, record.meta.dataArchive)
        val extFile = backupRepository.archiveFile(record, record.meta.externalArchive)

        if (dataFile == null && extFile == null) {
            return@withContext OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = false,
                message = "该备份不包含可恢复的数据",
            )
        }

        onProgress(
            EngineProgress(
                packageName = app.packageName,
                label = app.label,
                phase = "准备恢复",
                detail = "校验当前权限与应用状态",
                completed = 0,
                total = 1,
            ),
        )

        if (record.meta.versionCode != app.versionCode) {
            onProgress(
                EngineProgress(
                    packageName = app.packageName,
                    label = app.label,
                    phase = "版本提示",
                    detail = "备份版本 ${record.meta.versionName} (${record.meta.versionCode}) 与已安装版本 ${app.versionName} (${app.versionCode}) 不同",
                    completed = 0,
                    total = 1,
                ),
            )
        }

        val verify = backupRepository.verify(record)
        if (verify.state == VerifyState.MISMATCH || verify.state == VerifyState.MISSING) {
            return@withContext OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = false,
                message = "备份归档校验失败：${verify.message}",
            )
        }

        Log.i(TAG, "restore ${app.packageName} mode=${mode} access=${status.access}")
        var dataRestored = false
        var externalRestored = false
        try {
            if (dataFile != null) {
                when {
                    status.access == DataAccess.FULL && runner != null -> {
                        onProgress(
                            EngineProgress(
                                packageName = app.packageName,
                                label = app.label,
                                phase = "恢复私有数据",
                                detail = "停止应用，替换数据目录并修复所有权/SELinux",
                                completed = 0,
                                total = 1,
                            ),
                        )
                        val script = rootRestoreScript(
                            app = app,
                            meta = record.meta,
                            dataFile = dataFile,
                            externalFile = null,
                            tar = tar,
                        )
                        runner.run(script, 0).requireOk("恢复 ${app.packageName} 私有数据")
                        dataRestored = true
                    }
                    status.access == DataAccess.DEBUGGABLE_ONLY && app.isDebuggable && runner != null -> {
                        onProgress(
                            EngineProgress(
                                packageName = app.packageName,
                                label = app.label,
                                phase = "恢复私有数据",
                                detail = "通过 run-as 解包 debuggable 应用数据",
                                completed = 0,
                                total = 1,
                            ),
                        )
                        val script = runAsRestoreScript(app.packageName, dataFile, tar)
                        runner.run(script, 0).requireOk("run-as 恢复 ${app.packageName}")
                        dataRestored = true
                    }
                    else -> return@withContext OperationResultItem(
                        packageName = app.packageName,
                        label = app.label,
                        success = false,
                        message = "当前权限模式无法恢复该应用的私有数据（需要 Root、Root 模式 Shizuku 或 debuggable 应用）",
                    )
                }
            }

            if (extFile != null) {
                if (status.access == DataAccess.FULL && runner != null) {
                    onProgress(
                        EngineProgress(
                            packageName = app.packageName,
                            label = app.label,
                            phase = "恢复外部数据",
                            detail = "解包 Android/data 与 Android/obb",
                            completed = 0,
                            total = 1,
                        ),
                    )
                    val script = shellExternalRestoreScript(app.packageName, extFile, tar)
                    runner.run(script, 0).requireOk("恢复 ${app.packageName} 外部数据")
                    externalRestored = true
                } else {
                    onProgress(
                        EngineProgress(
                            packageName = app.packageName,
                            label = app.label,
                            phase = "外部数据",
                            detail = "当前模式跳过外部数据（需要 Root 或 Shizuku full）",
                            completed = 0,
                            total = 1,
                        ),
                    )
                }
            }
        } catch (e: OperationCancelledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext OperationResultItem(
                packageName = app.packageName,
                label = app.label,
                success = false,
                message = e.message ?: e.javaClass.simpleName,
            )
        }

        val parts = buildList {
            if (dataRestored) add("私有数据")
            if (externalRestored) add("外部数据")
        }
        OperationResultItem(
            packageName = app.packageName,
            label = app.label,
            success = dataRestored || externalRestored,
            message = if (parts.isEmpty()) "没有恢复任何数据" else "已恢复：" + parts.joinToString(" + "),
            bytes = (dataFile?.length() ?: 0) + (extFile?.length() ?: 0),
            backupDir = record.dir.absolutePath,
        )
    }

    private suspend fun copyApks(
        app: AppEntry,
        snapshotDir: File,
        runner: ShellRunner?,
        status: PrivilegeStatus,
    ): Pair<List<String>, Long> {
        val apkDir = File(snapshotDir, "apk")
        apkDir.mkdirs()
        val paths = app.apkPaths
        if (paths.isEmpty()) return emptyList<String>() to 0L
        val names = mutableListOf<String>()
        var bytes = 0L
        paths.forEachIndexed { index, source ->
            val name = if (index == 0) "base.apk" else "split_$index.apk"
            val target = File(apkDir, name)
            val copied = runCatching {
                File(source).inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }.isSuccess
            if (!copied && runner != null && status.access != DataAccess.APK_ONLY) {
                val script = "mkdir -p ${ShellScripts.quote(apkDir.absolutePath)} && cp ${ShellScripts.quote(source)} ${ShellScripts.quote(target.absolutePath)} && chmod 0644 ${ShellScripts.quote(target.absolutePath)}"
                runCatching { runner.run(script, 120_000) }.getOrNull()?.let {
                    if (it.ok && target.isFile) {
                        names += name
                        bytes += target.length()
                    }
                }
            } else if (copied) {
                names += name
                bytes += target.length()
            }
        }
        return names to bytes
    }

    private companion object {
        const val TAG = "DataArkEngine"
    }

    private fun internalExcludeArgs(pkg: String, tar: TarSupport): String {
        if (!tar.exclude) return ""
        return listOf("cache", "code_cache", "lib", "no_backup", ".ota")
            .flatMap { name ->
                listOf(
                    "/data/user/0/$pkg/$name",
                    "/data/user_de/0/$pkg/$name",
                )
            }
            .joinToString(" ") { path ->
                "--exclude=" + path.removePrefix("/")
            }
    }

    private fun externalExcludeArgs(pkg: String, tar: TarSupport): String {
        if (!tar.exclude) return ""
        return listOf(
            "/storage/emulated/0/Android/data/$pkg/cache",
            "/storage/emulated/0/Android/obb/$pkg/cache",
            "/storage/emulated/0/Android/data/$pkg/Backup_*",
        ).joinToString(" ") { path ->
            "--exclude=" + path.removePrefix("/")
        }
    }

    private fun rootBackupScript(
        pkg: String,
        dest: File,
        dataFile: File,
        externalFile: File?,
        includeData: Boolean,
        includeDe: Boolean,
        includeExternal: Boolean,
        tar: TarSupport,
        compress: Boolean,
    ): String {
        val dataFlags = ShellScripts.tarCreateFlags(tar, selinux = true, compress = compress)
        val dataExcludes = internalExcludeArgs(pkg, tar)
        val extFlags = ShellScripts.tarCreateFlags(tar, selinux = false, compress = compress)
        val extExcludes = externalExcludeArgs(pkg, tar)
        val q = ShellScripts::quote
        val externalBlock = if (includeExternal && externalFile != null) {
            """
            EXT_OUT=${q(externalFile.absolutePath)}
            rm -f "${'$'}EXT_OUT"
            if [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ] || [ -d "/storage/emulated/0/Android/obb/${'$'}PKG" ]; then
              if [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ] && [ -d "/storage/emulated/0/Android/obb/${'$'}PKG" ]; then
                tar $extFlags "${'$'}EXT_OUT" $extExcludes "/storage/emulated/0/Android/data/${'$'}PKG" "/storage/emulated/0/Android/obb/${'$'}PKG"
              elif [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ]; then
                tar $extFlags "${'$'}EXT_OUT" $extExcludes "/storage/emulated/0/Android/data/${'$'}PKG"
              else
                tar $extFlags "${'$'}EXT_OUT" $extExcludes "/storage/emulated/0/Android/obb/${'$'}PKG"
              fi
              chmod 0644 "${'$'}EXT_OUT" 2>/dev/null || true
              printf 'EXT=1\n'
            else
              printf 'EXT=0\n'
            fi
            """.trimIndent()
        } else {
            "printf 'EXT=0\\n'"
        }
        return """
            set -e
            PKG=${q(pkg)}
            DEST=${q(dest.absolutePath)}
            DATA_OUT=${q(dataFile.absolutePath)}
            mkdir -p "${'$'}DEST"
            am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
            rm -f "${'$'}DATA_OUT"
            CE="/data/user/0/${'$'}PKG"
            DE="/data/user_de/0/${'$'}PKG"
            if [ -d "${'$'}CE" ] && [ "${if (includeDe) 1 else 0}" = "1" ] && [ -d "${'$'}DE" ]; then
              tar $dataFlags "${'$'}DATA_OUT" $dataExcludes "${'$'}CE" "${'$'}DE"
              chmod 0644 "${'$'}DATA_OUT" 2>/dev/null || true
              printf 'DATA=1\nDE=1\n'
            elif [ -d "${'$'}CE" ]; then
              tar $dataFlags "${'$'}DATA_OUT" $dataExcludes "${'$'}CE"
              chmod 0644 "${'$'}DATA_OUT" 2>/dev/null || true
              printf 'DATA=1\nDE=0\n'
            elif [ -d "${'$'}DE" ] && [ "${if (includeDe) 1 else 0}" = "1" ]; then
              tar $dataFlags "${'$'}DATA_OUT" $dataExcludes "${'$'}DE"
              chmod 0644 "${'$'}DATA_OUT" 2>/dev/null || true
              printf 'DATA=1\nDE=1\n'
            else
              printf 'DATA=0\nDE=0\n'
            fi
            $externalBlock
            printf 'DONE=1\n'
        """.trimIndent()
    }

    private fun runAsBackupScript(
        pkg: String,
        dest: File,
        dataFile: File,
        tar: TarSupport,
        compress: Boolean,
    ): String {
        val q = ShellScripts::quote
        val tarFlags = if (tar.numericOwner) "--numeric-owner" else ""
        val create = if (compress) "-czf" else "-cf"
        val excludes = if (tar.exclude) {
            listOf("cache", "code_cache", "lib", "no_backup", ".ota")
                .joinToString(" ") { "--exclude=./$it" }
        } else {
            ""
        }
        return """
            set -e
            PKG=${q(pkg)}
            DEST=${q(dest.absolutePath)}
            DATA_OUT=${q(dataFile.absolutePath)}
            mkdir -p "${'$'}DEST"
            am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
            run-as "${'$'}PKG" sh -c "cd /data/data/${'$'}PKG && tar $tarFlags $create - $excludes ." > "${'$'}DATA_OUT"
            chmod 0644 "${'$'}DATA_OUT" 2>/dev/null || true
            printf 'DATA=1\nDE=0\n'
        """.trimIndent()
    }

    private fun shellExternalBackupScript(
        pkg: String,
        dest: File,
        externalFile: File,
        tar: TarSupport,
        compress: Boolean,
    ): String {
        val q = ShellScripts::quote
        val flags = ShellScripts.tarCreateFlags(tar, selinux = false, compress = compress)
        val excludes = externalExcludeArgs(pkg, tar)
        return """
            set -e
            PKG=${q(pkg)}
            DEST=${q(dest.absolutePath)}
            EXT_OUT=${q(externalFile.absolutePath)}
            mkdir -p "${'$'}DEST"
            rm -f "${'$'}EXT_OUT"
            if [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ] || [ -d "/storage/emulated/0/Android/obb/${'$'}PKG" ]; then
              if [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ] && [ -d "/storage/emulated/0/Android/obb/${'$'}PKG" ]; then
                tar $flags "${'$'}EXT_OUT" $excludes "/storage/emulated/0/Android/data/${'$'}PKG" "/storage/emulated/0/Android/obb/${'$'}PKG"
              elif [ -d "/storage/emulated/0/Android/data/${'$'}PKG" ]; then
                tar $flags "${'$'}EXT_OUT" $excludes "/storage/emulated/0/Android/data/${'$'}PKG"
              else
                tar $flags "${'$'}EXT_OUT" $excludes "/storage/emulated/0/Android/obb/${'$'}PKG"
              fi
              chmod 0644 "${'$'}EXT_OUT" 2>/dev/null || true
              printf 'EXT=1\n'
            else
              printf 'EXT=0\n'
            fi
        """.trimIndent()
    }

    private fun rootRestoreScript(
        app: AppEntry,
        meta: BackupMeta,
        dataFile: File,
        externalFile: File?,
        tar: TarSupport,
    ): String {
        val q = ShellScripts::quote
        val extractData = ShellScripts.tarExtractFlags(
            tar,
            selinux = meta.tarSelinux,
            compress = meta.compression == "gzip",
        )
        val extFlags = buildString {
            if (tar.numericOwner) append("--numeric-owner ")
            if (tar.noSameOwner) append("--no-same-owner ")
            append(if (meta.compression == "gzip") "-xzf" else "-xf")
        }
        val moveCe = if (meta.hasInternalData) "1" else "0"
        val moveDe = if (meta.hasDeviceProtectedData) "1" else "0"
        val externalBlock = if (externalFile != null && meta.hasExternalData) {
            """
            EXT_ARCHIVE=${q(externalFile.absolutePath)}
            if [ -f "${'$'}EXT_ARCHIVE" ]; then
              rm -rf "/storage/emulated/0/Android/data/${'$'}PKG" "/storage/emulated/0/Android/obb/${'$'}PKG" 2>/dev/null || true
              tar $extFlags "${'$'}EXT_ARCHIVE" -C /
              printf 'EXTERNAL_RESTORED=1\n'
            fi
            """.trimIndent()
        } else {
            ""
        }
        return """
            set -e
            PKG=${q(app.packageName)}
            UID=${q(app.uid.toString())}
            DATA_ARCHIVE=${q(dataFile.absolutePath)}
            BAK="/data/local/tmp/.dataark_restore_${app.packageName}_$$"
            CE_CTX=$(stat -c %C "/data/user/0/${'$'}PKG" 2>/dev/null || true)
            DE_CTX=$(stat -c %C "/data/user_de/0/${'$'}PKG" 2>/dev/null || true)
            mkdir -p "${'$'}BAK"
            am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
            restore_back() {
              rm -rf "/data/user/0/${'$'}PKG" "/data/user_de/0/${'$'}PKG"
              if [ -d "${'$'}BAK/ce" ]; then mkdir -p /data/user/0; mv "${'$'}BAK/ce" "/data/user/0/${'$'}PKG"; fi
              if [ -d "${'$'}BAK/de" ]; then mkdir -p /data/user_de/0; mv "${'$'}BAK/de" "/data/user_de/0/${'$'}PKG"; fi
            }
            cleanup() {
              rc=${'$'}?
              if [ ${'$'}rc -ne 0 ]; then restore_back; fi
              rm -rf "${'$'}BAK"
              exit ${'$'}rc
            }
            trap cleanup EXIT
            if [ -f "${'$'}DATA_ARCHIVE" ]; then
              if [ "${moveCe}" = "1" ] && [ -d "/data/user/0/${'$'}PKG" ]; then mv "/data/user/0/${'$'}PKG" "${'$'}BAK/ce"; fi
              if [ "${moveDe}" = "1" ] && [ -d "/data/user_de/0/${'$'}PKG" ]; then mv "/data/user_de/0/${'$'}PKG" "${'$'}BAK/de"; fi
              tar $extractData "${'$'}DATA_ARCHIVE" -C /
              if [ -d "/data/user/0/${'$'}PKG" ]; then
                chown -R "${'$'}UID":"${'$'}UID" "/data/user/0/${'$'}PKG" 2>/dev/null || true
                chmod 700 "/data/user/0/${'$'}PKG" 2>/dev/null || true
              fi
              if [ -d "/data/user_de/0/${'$'}PKG" ]; then
                chown -R "${'$'}UID":"${'$'}UID" "/data/user_de/0/${'$'}PKG" 2>/dev/null || true
                chmod 700 "/data/user_de/0/${'$'}PKG" 2>/dev/null || true
              fi
              if command -v chcon >/dev/null 2>&1; then
                case "${'$'}CE_CTX" in
                  u:*) chcon -hR "${'$'}CE_CTX" "/data/user/0/${'$'}PKG" 2>/dev/null || true ;;
                  *) if command -v restorecon >/dev/null 2>&1; then restorecon -RF "/data/user/0/${'$'}PKG" 2>/dev/null || true; fi ;;
                esac
                case "${'$'}DE_CTX" in
                  u:*) chcon -hR "${'$'}DE_CTX" "/data/user_de/0/${'$'}PKG" 2>/dev/null || true ;;
                  *) if command -v restorecon >/dev/null 2>&1; then restorecon -RF "/data/user_de/0/${'$'}PKG" 2>/dev/null || true; fi ;;
                esac
              elif command -v restorecon >/dev/null 2>&1; then
                restorecon -RF "/data/user/0/${'$'}PKG" "/data/user_de/0/${'$'}PKG" 2>/dev/null || true
              fi
              printf 'DATA_RESTORED=1\n'
            fi
            $externalBlock
            trap - EXIT
            rm -rf "${'$'}BAK"
            printf 'RESTORED=1\n'
        """.trimIndent()
    }

    private fun runAsRestoreScript(pkg: String, dataFile: File, tar: TarSupport): String {
        val q = ShellScripts::quote
        val tarFlags = if (tar.numericOwner) "--numeric-owner" else ""
        return """
            set -e
            PKG=${q(pkg)}
            DATA_ARCHIVE=${q(dataFile.absolutePath)}
            am force-stop "${'$'}PKG" >/dev/null 2>&1 || true
            cat "${'$'}DATA_ARCHIVE" | run-as "${'$'}PKG" sh -c "cd /data/data/${'$'}PKG && find . -mindepth 1 -maxdepth 1 -exec rm -rf {} + && tar $tarFlags -xpf -"
            printf 'RESTORED=1\n'
        """.trimIndent()
    }

    private fun shellExternalRestoreScript(pkg: String, externalFile: File, tar: TarSupport): String {
        val q = ShellScripts::quote
        val flags = buildString {
            if (tar.numericOwner) append("--numeric-owner ")
            if (tar.noSameOwner) append("--no-same-owner ")
            append(if (externalFile.name.endsWith(".gz")) "-xzf" else "-xf")
        }
        return """
            set -e
            PKG=${q(pkg)}
            EXT_ARCHIVE=${q(externalFile.absolutePath)}
            rm -rf "/storage/emulated/0/Android/data/${'$'}PKG" "/storage/emulated/0/Android/obb/${'$'}PKG" 2>/dev/null || true
            tar $flags "${'$'}EXT_ARCHIVE" -C /
            printf 'EXTERNAL_RESTORED=1\n'
        """.trimIndent()
    }


    private fun ShellResult.requireOk(action: String): String {
        if (!ok) {
            val detail = combined.take(2_000).ifBlank { "exit code $code" }
            throw IOException("$action 失败：$detail")
        }
        return combined
    }
}
