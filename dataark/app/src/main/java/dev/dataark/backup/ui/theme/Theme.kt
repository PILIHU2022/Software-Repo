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

package dev.dataark.backup.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF58E1C1),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005142),
    onPrimaryContainer = Color(0xFF7AF8D8),
    secondary = Color(0xFFB4CCC4),
    background = Color(0xFF0B1220),
    surface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFF1C2738),
    onSurface = Color(0xFFE4EAF2),
    onSurfaceVariant = Color(0xFFBAC8D8),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF78F8D8),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635D),
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFDBE5E1),
    onSurface = Color(0xFF171D1B),
    onSurfaceVariant = Color(0xFF3F4946),
    error = Color(0xFFBA1A1A),
)

@Composable
fun DataArkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
