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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.android.settingslib.spa.framework.util.SystemProperties

/**
 * The Material 3 Theme for Settings.
 */
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val isDarkTheme = isSystemInDarkTheme()

    MaterialTheme(
        colorScheme = materialColorScheme(isDarkTheme),
        typography = rememberSettingsTypography(),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
            content()
        }
    }
}

val isSpaExpressiveEnabled
    by lazy { SystemProperties.getBoolean("is_expressive_design_enabled", false) }
