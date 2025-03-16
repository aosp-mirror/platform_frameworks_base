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

package com.android.compose.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.android.compose.theme.AndroidColorScheme.Companion.color
import com.android.compose.theme.typography.TypeScaleTokens
import com.android.compose.theme.typography.TypefaceNames
import com.android.compose.theme.typography.TypefaceTokens
import com.android.compose.theme.typography.TypographyTokens
import com.android.compose.theme.typography.platformTypography
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.compose.windowsizeclass.calculateWindowSizeClass
import com.android.internal.R

/** The Material 3 theme that should wrap all Platform Composables. */
@Composable
fun PlatformTheme(isDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current

    val colorScheme = remember(context, isDarkTheme) { platformColorScheme(isDarkTheme, context) }
    val androidColorScheme = remember(context) { AndroidColorScheme(context) }
    val typefaceNames = remember(context) { TypefaceNames.get(context) }
    val typography =
        remember(typefaceNames) {
            platformTypography(TypographyTokens(TypeScaleTokens(TypefaceTokens(typefaceNames))))
        }
    val windowSizeClass = calculateWindowSizeClass()

    MaterialTheme(colorScheme = colorScheme, typography = typography) {
        CompositionLocalProvider(
            LocalAndroidColorScheme provides androidColorScheme,
            LocalWindowSizeClass provides windowSizeClass,
            content = content,
        )
    }
}

private fun platformColorScheme(isDarkTheme: Boolean, context: Context): ColorScheme {
    return if (isDarkTheme) {
        dynamicDarkColorScheme(context)
            .copy(
                inverseSurface = color(context, R.color.system_inverse_surface_dark),
                inverseOnSurface = color(context, R.color.system_inverse_on_surface_dark),
                inversePrimary = color(context, R.color.system_inverse_primary_dark),
                error = color(context, R.color.system_error_dark),
                onError = color(context, R.color.system_on_error_dark),
                errorContainer = color(context, R.color.system_error_container_dark),
                onErrorContainer = color(context, R.color.system_on_error_container_dark),
            )
    } else {
        dynamicLightColorScheme(context)
            .copy(
                inverseSurface = color(context, R.color.system_inverse_surface_light),
                inverseOnSurface = color(context, R.color.system_inverse_on_surface_light),
                inversePrimary = color(context, R.color.system_inverse_primary_light),
                error = color(context, R.color.system_error_light),
                onError = color(context, R.color.system_on_error_light),
                errorContainer = color(context, R.color.system_error_container_light),
                onErrorContainer = color(context, R.color.system_on_error_container_light),
            )
    }
}
