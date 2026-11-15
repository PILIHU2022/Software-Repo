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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/** A process-wide operation state shared by the foreground service and the UI. */
object OperationBus {
    private val _state = MutableStateFlow(OperationState())
    val state: StateFlow<OperationState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private val logLines = ArrayDeque<String>()

    fun begin(kind: OperationKind, total: Int) {
        cancelRequested.set(false)
        logLines.clear()
        _state.value = OperationState(
            kind = kind,
            active = true,
            total = total,
            startedAt = System.currentTimeMillis(),
        )
    }

    fun onProgress(progress: EngineProgress) {
        _state.update { current ->
            val next = current.copy(
                currentPackage = progress.packageName,
                currentLabel = progress.label,
                phase = progress.phase,
                detail = progress.detail,
                completed = if (progress.total > 0) progress.completed else current.completed,
                total = if (progress.total > 0) progress.total else current.total,
            )
            if (progress.result != null) {
                next.copy(results = next.results + progress.result)
            } else {
                next
            }
        }
        if (progress.phase.isNotBlank()) {
            synchronized(logLines) {
                logLines.addLast("${progress.label}: ${progress.phase} ${progress.detail}".trim())
                while (logLines.size > 200) logLines.removeFirst()
            }
        }
    }

    fun finish(error: String? = null, cancelled: Boolean = false) {
        _state.update {
            it.copy(
                active = false,
                finished = true,
                cancelled = cancelled,
                fatalError = error,
                finishedAt = System.currentTimeMillis(),
                phase = when {
                    error != null -> "失败"
                    cancelled -> "已取消"
                    else -> "完成"
                },
            )
        }
    }

    fun requestCancel() {
        cancelRequested.set(true)
        _state.update { it.copy(detail = "正在请求取消…当前应用完成后停止") }
    }

    fun isCancelRequested(): Boolean = cancelRequested.get()

    fun reset() {
        cancelRequested.set(false)
        _state.value = OperationState()
    }

    fun logs(): List<String> = synchronized(logLines) { logLines.toList() }
}
