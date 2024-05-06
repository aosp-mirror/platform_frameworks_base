/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.spa.framework.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
internal fun materialColorScheme(isDarkTheme: Boolean): ColorScheme {
    val context = LocalContext.current
    return remember(isDarkTheme) {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (isDarkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            }
            isDarkTheme -> darkColorScheme()
            else -> lightColorScheme()
        }
    }
}

val ColorScheme.divider: Color
    get() = onSurface.copy(SettingsOpacity.Divider)

val ColorScheme.surfaceTone: Color
    get() = primary.copy(SettingsOpacity.SurfaceTone)

/** The overall background color in Settings. */
val ColorScheme.settingsBackground: Color
    get() = surfaceContainer
