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

package dev.dataark.backup.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.dataark.backup.MainActivity
import dev.dataark.backup.R
import dev.dataark.backup.core.AppRepository
import dev.dataark.backup.core.BackupEngine
import dev.dataark.backup.core.BackupOptions
import dev.dataark.backup.core.BackupRepository
import dev.dataark.backup.core.BackupScope
import dev.dataark.backup.core.EngineProgress
import dev.dataark.backup.core.OperationBus
import dev.dataark.backup.core.OperationKind
import dev.dataark.backup.core.OperationResultItem
import dev.dataark.backup.privilege.PrivilegeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class BackupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannel()
        // Start the foreground state immediately: on slow devices the 5s window between
        // startForegroundService() and startForeground() can otherwise expire.
        runCatching { startInForeground("准备中…") }
            .onFailure { Log.e(TAG, "initial startForeground failed", it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} extras=${intent?.extras}")
        when (intent?.action) {
            ACTION_CANCEL -> {
                if (OperationBus.state.value.active) {
                    OperationBus.requestCancel()
                } else {
                    finishForeground()
                }
                return START_NOT_STICKY
            }
            ACTION_BACKUP -> {
                startInForeground("准备备份")
                val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)?.toList().orEmpty()
                val options = BackupOptions(
                    includeInternalData = intent.getBooleanExtra(EXTRA_INTERNAL, true),
                    includeDeviceProtectedData = intent.getBooleanExtra(EXTRA_DE, true),
                    includeExternalData = intent.getBooleanExtra(EXTRA_EXTERNAL, true),
                    includeApk = intent.getBooleanExtra(EXTRA_APK, true),
                    compress = intent.getBooleanExtra(EXTRA_COMPRESS, false),
                )
                job?.cancel()
                job = scope.launch { runBackup(packages, options) }
                return START_NOT_STICKY
            }
            ACTION_RESTORE -> {
                startInForeground("准备恢复")
                val recordId = intent.getStringExtra(EXTRA_RECORD_ID)
                job?.cancel()
                job = scope.launch { runRestore(recordId) }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runBackup(packages: List<String>, options: BackupOptions) {
        try {
            PrivilegeManager.refresh()
            val apps = AppRepository(this).load(BackupScope.ALL)
                .filter { it.packageName in packages }
                .let { selected ->
                    packages.mapNotNull { pkg -> selected.firstOrNull { it.packageName == pkg } }
                }
            Log.i(TAG, "backup apps=${apps.map { it.packageName }}")
            if (apps.isEmpty()) {
                OperationBus.finish(error = "没有选择任何应用")
                finishForeground()
                return
            }
            val needsPersistent = options.includeInternalData ||
                options.includeDeviceProtectedData ||
                options.includeExternalData
            val storage = BackupRepository(this).storageInfo()
            if (needsPersistent && !storage.persistent) {
                OperationBus.finish(error = "数据备份需要所有文件访问权限；当前目录为应用专属目录，系统会阻止读取。请授权后重试。")
                finishForeground()
                return
            }
            OperationBus.begin(OperationKind.BACKUP, apps.size)
            updateForeground("备份 ${apps.size} 个应用…", 0, apps.size)
            val engine = BackupEngine(this, AppRepository(this), BackupRepository(this))
            engine.backup(
                apps = apps,
                options = options,
                onProgress = { progress -> handleProgress(progress, OperationKind.BACKUP) },
                isCancelled = { OperationBus.isCancelRequested() },
            )
            OperationBus.finish(cancelled = OperationBus.isCancelRequested())
        } catch (e: CancellationException) {
            OperationBus.finish(cancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "backup failed", e)
            OperationBus.finish(error = e.message ?: e.javaClass.simpleName)
        } finally {
            finishForeground()
        }
    }

    private suspend fun runRestore(recordId: String?) {
        try {
            if (recordId == null) {
                OperationBus.finish(error = "未指定备份")
                finishForeground()
                return
            }
            PrivilegeManager.refresh()
            val repository = BackupRepository(this)
            val record = repository.find(recordId)
            if (record == null) {
                OperationBus.finish(error = "备份记录不存在：$recordId")
                finishForeground()
                return
            }
            OperationBus.begin(OperationKind.RESTORE, 1)
            updateForeground("恢复 ${record.label}…", 0, 1)
            val engine = BackupEngine(this, AppRepository(this), repository)
            val result = engine.restore(
                record = record,
                onProgress = { progress -> handleProgress(progress, OperationKind.RESTORE) },
                isCancelled = { OperationBus.isCancelRequested() },
            )
            OperationBus.onProgress(
                EngineProgress(
                    packageName = result.packageName,
                    label = result.label,
                    phase = if (result.success) "完成" else "失败",
                    detail = result.message,
                    completed = 1,
                    total = 1,
                    result = result,
                ),
            )
            OperationBus.finish(
                error = if (result.success) null else result.message,
                cancelled = OperationBus.isCancelRequested(),
            )
        } catch (e: CancellationException) {
            OperationBus.finish(cancelled = true)
        } catch (e: Exception) {
            Log.e(TAG, "restore failed", e)
            OperationBus.finish(error = e.message ?: e.javaClass.simpleName)
        } finally {
            finishForeground()
        }
    }

    private fun handleProgress(progress: EngineProgress, kind: OperationKind) {
        OperationBus.onProgress(progress)
        updateForeground(
            "${progress.label} · ${progress.phase}",
            progress.completed,
            progress.total.coerceAtLeast(1),
        )
        if (progress.result != null && !progress.result.success) {
            updateForeground("${progress.result.label} 失败", progress.completed, progress.total)
        }
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text, 0, 1, true)
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            Log.i(TAG, "startForeground ok")
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground failed", t)
            throw t
        }
    }

    private fun updateForeground(text: String, done: Int, total: Int) {
        val notification = buildNotification(text, done, total, false)
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun finishForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, done: Int, total: Int, indeterminate: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BackupService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dataark)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0, indeterminate)
            .addAction(0, "取消", cancelIntent)
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "备份与恢复",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "数据方舟的后台备份/恢复进度"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "dataark_operations"
        private const val TAG = "DataArkService"
        private const val NOTIFICATION_ID = 0x0DA7
        const val ACTION_BACKUP = "dev.dataark.backup.action.BACKUP"
        const val ACTION_RESTORE = "dev.dataark.backup.action.RESTORE"
        const val ACTION_CANCEL = "dev.dataark.backup.action.CANCEL"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_INTERNAL = "internal"
        const val EXTRA_DE = "de"
        const val EXTRA_EXTERNAL = "external"
        const val EXTRA_APK = "apk"
        const val EXTRA_COMPRESS = "compress"

        fun startBackup(context: Context, packages: List<String>, options: BackupOptions) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_BACKUP
                putExtra(EXTRA_PACKAGES, packages.toTypedArray())
                putExtra(EXTRA_INTERNAL, options.includeInternalData)
                putExtra(EXTRA_DE, options.includeDeviceProtectedData)
                putExtra(EXTRA_EXTERNAL, options.includeExternalData)
                putExtra(EXTRA_APK, options.includeApk)
                putExtra(EXTRA_COMPRESS, options.compress)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startRestore(context: Context, recordId: String) {
            val intent = Intent(context, BackupService::class.java).apply {
                action = ACTION_RESTORE
                putExtra(EXTRA_RECORD_ID, recordId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
