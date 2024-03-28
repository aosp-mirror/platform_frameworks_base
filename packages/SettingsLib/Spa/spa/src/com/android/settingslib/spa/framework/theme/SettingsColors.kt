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

import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

data class SettingsColorScheme(
    val background: Color = Color.Unspecified,
    val categoryTitle: Color = Color.Unspecified,
    val surface: Color = Color.Unspecified,
    val surfaceHeader: Color = Color.Unspecified,
    val secondaryText: Color = Color.Unspecified,
    val primaryContainer: Color = Color.Unspecified,
    val onPrimaryContainer: Color = Color.Unspecified,
)

internal val LocalColorScheme = staticCompositionLocalOf { SettingsColorScheme() }

@Composable
internal fun settingsColorScheme(isDarkTheme: Boolean): SettingsColorScheme {
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

/**
 * Creates a light dynamic color scheme.
 *
 * Use this function to create a color scheme based off the system wallpaper. If the developer
 * changes the wallpaper this color scheme will change accordingly. This dynamic scheme is a
 * light theme variant.
 *
 * @param context The context required to get system resource data.
 */
@VisibleForTesting
internal fun dynamicLightColorScheme(context: Context): SettingsColorScheme {
    val tonalPalette = dynamicTonalPalette(context)
    return SettingsColorScheme(
        background = tonalPalette.neutral95,
        categoryTitle = tonalPalette.primary40,
        surface = tonalPalette.neutral99,
        surfaceHeader = tonalPalette.neutral90,
        secondaryText = tonalPalette.neutralVariant30,
        primaryContainer = tonalPalette.primary90,
        onPrimaryContainer = tonalPalette.neutral10,
    )
}

/**
 * Creates a dark dynamic color scheme.
 *
 * Use this function to create a color scheme based off the system wallpaper. If the developer
 * changes the wallpaper this color scheme will change accordingly. This dynamic scheme is a dark
 * theme variant.
 *
 * @param context The context required to get system resource data.
 */
@VisibleForTesting
internal fun dynamicDarkColorScheme(context: Context): SettingsColorScheme {
    val tonalPalette = dynamicTonalPalette(context)
    return SettingsColorScheme(
        background = tonalPalette.neutral10,
        categoryTitle = tonalPalette.primary90,
        surface = tonalPalette.neutral20,
        surfaceHeader = tonalPalette.neutral30,
        secondaryText = tonalPalette.neutralVariant80,
        primaryContainer = tonalPalette.secondary90,
        onPrimaryContainer = tonalPalette.neutral10,
    )
}

@VisibleForTesting
internal fun darkColorScheme(): SettingsColorScheme {
    val tonalPalette = tonalPalette()
    return SettingsColorScheme(
        background = tonalPalette.neutral10,
        categoryTitle = tonalPalette.primary90,
        surface = tonalPalette.neutral20,
        surfaceHeader = tonalPalette.neutral30,
        secondaryText = tonalPalette.neutralVariant80,
        primaryContainer = tonalPalette.secondary90,
        onPrimaryContainer = tonalPalette.neutral10,
    )
}

@VisibleForTesting
internal fun lightColorScheme(): SettingsColorScheme {
    val tonalPalette = tonalPalette()
    return SettingsColorScheme(
        background = tonalPalette.neutral95,
        categoryTitle = tonalPalette.primary40,
        surface = tonalPalette.neutral99,
        surfaceHeader = tonalPalette.neutral90,
        secondaryText = tonalPalette.neutralVariant30,
        primaryContainer = tonalPalette.primary90,
        onPrimaryContainer = tonalPalette.neutral10,
    )
}
