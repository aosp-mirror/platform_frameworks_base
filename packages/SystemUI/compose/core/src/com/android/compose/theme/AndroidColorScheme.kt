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

import android.annotation.ColorInt
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.android.internal.R

/** CompositionLocal used to pass [AndroidColorScheme] down the tree. */
val LocalAndroidColorScheme =
    staticCompositionLocalOf<AndroidColorScheme> {
        throw IllegalStateException(
            "No AndroidColorScheme configured. Make sure to use LocalAndroidColorScheme in a " +
                "Composable surrounded by a PlatformTheme {}."
        )
    }

/**
 * The Android color scheme.
 *
 * Important: Use M3 colors from MaterialTheme.colorScheme whenever possible instead. In the future,
 * most of the colors in this class will be removed in favor of their M3 counterpart.
 */
class AndroidColorScheme(context: Context) {
    val onSecondaryFixedVariant = getColor(context, R.attr.materialColorOnSecondaryFixedVariant)
    val onTertiaryFixedVariant = getColor(context, R.attr.materialColorOnTertiaryFixedVariant)
    val surfaceContainerLowest = getColor(context, R.attr.materialColorSurfaceContainerLowest)
    val onPrimaryFixedVariant = getColor(context, R.attr.materialColorOnPrimaryFixedVariant)
    val onSecondaryContainer = getColor(context, R.attr.materialColorOnSecondaryContainer)
    val onTertiaryContainer = getColor(context, R.attr.materialColorOnTertiaryContainer)
    val surfaceContainerLow = getColor(context, R.attr.materialColorSurfaceContainerLow)
    val onPrimaryContainer = getColor(context, R.attr.materialColorOnPrimaryContainer)
    val secondaryFixedDim = getColor(context, R.attr.materialColorSecondaryFixedDim)
    val onErrorContainer = getColor(context, R.attr.materialColorOnErrorContainer)
    val onSecondaryFixed = getColor(context, R.attr.materialColorOnSecondaryFixed)
    val onSurfaceInverse = getColor(context, R.attr.materialColorOnSurfaceInverse)
    val tertiaryFixedDim = getColor(context, R.attr.materialColorTertiaryFixedDim)
    val onTertiaryFixed = getColor(context, R.attr.materialColorOnTertiaryFixed)
    val primaryFixedDim = getColor(context, R.attr.materialColorPrimaryFixedDim)
    val secondaryContainer = getColor(context, R.attr.materialColorSecondaryContainer)
    val errorContainer = getColor(context, R.attr.materialColorErrorContainer)
    val onPrimaryFixed = getColor(context, R.attr.materialColorOnPrimaryFixed)
    val primaryInverse = getColor(context, R.attr.materialColorPrimaryInverse)
    val secondaryFixed = getColor(context, R.attr.materialColorSecondaryFixed)
    val surfaceInverse = getColor(context, R.attr.materialColorSurfaceInverse)
    val surfaceVariant = getColor(context, R.attr.materialColorSurfaceVariant)
    val tertiaryContainer = getColor(context, R.attr.materialColorTertiaryContainer)
    val tertiaryFixed = getColor(context, R.attr.materialColorTertiaryFixed)
    val primaryContainer = getColor(context, R.attr.materialColorPrimaryContainer)
    val onBackground = getColor(context, R.attr.materialColorOnBackground)
    val primaryFixed = getColor(context, R.attr.materialColorPrimaryFixed)
    val onSecondary = getColor(context, R.attr.materialColorOnSecondary)
    val onTertiary = getColor(context, R.attr.materialColorOnTertiary)
    val surfaceDim = getColor(context, R.attr.materialColorSurfaceDim)
    val surfaceBright = getColor(context, R.attr.materialColorSurfaceBright)
    val error = getColor(context, R.attr.materialColorError)
    val onError = getColor(context, R.attr.materialColorOnError)
    val surface = getColor(context, R.attr.materialColorSurface)
    val surfaceContainerHigh = getColor(context, R.attr.materialColorSurfaceContainerHigh)
    val surfaceContainerHighest = getColor(context, R.attr.materialColorSurfaceContainerHighest)
    val onSurfaceVariant = getColor(context, R.attr.materialColorOnSurfaceVariant)
    val outline = getColor(context, R.attr.materialColorOutline)
    val outlineVariant = getColor(context, R.attr.materialColorOutlineVariant)
    val onPrimary = getColor(context, R.attr.materialColorOnPrimary)
    val onSurface = getColor(context, R.attr.materialColorOnSurface)
    val surfaceContainer = getColor(context, R.attr.materialColorSurfaceContainer)
    val primary = getColor(context, R.attr.materialColorPrimary)
    val secondary = getColor(context, R.attr.materialColorSecondary)
    val tertiary = getColor(context, R.attr.materialColorTertiary)

    @Deprecated("Use the new android tokens: go/sysui-colors")
    val deprecated = DeprecatedValues(context)

    class DeprecatedValues(context: Context) {
        val colorPrimary = getColor(context, R.attr.colorPrimary)
        val colorPrimaryDark = getColor(context, R.attr.colorPrimaryDark)
        val colorAccent = getColor(context, R.attr.colorAccent)
        val colorAccentPrimary = getColor(context, R.attr.colorAccentPrimary)
        val colorAccentSecondary = getColor(context, R.attr.colorAccentSecondary)
        val colorAccentTertiary = getColor(context, R.attr.colorAccentTertiary)
        val colorAccentPrimaryVariant = getColor(context, R.attr.colorAccentPrimaryVariant)
        val colorAccentSecondaryVariant = getColor(context, R.attr.colorAccentSecondaryVariant)
        val colorAccentTertiaryVariant = getColor(context, R.attr.colorAccentTertiaryVariant)
        val colorSurface = getColor(context, R.attr.colorSurface)
        val colorSurfaceHighlight = getColor(context, R.attr.colorSurfaceHighlight)
        val colorSurfaceVariant = getColor(context, R.attr.colorSurfaceVariant)
        val colorSurfaceHeader = getColor(context, R.attr.colorSurfaceHeader)
        val colorError = getColor(context, R.attr.colorError)
        val colorBackground = getColor(context, R.attr.colorBackground)
        val colorBackgroundFloating = getColor(context, R.attr.colorBackgroundFloating)
        val panelColorBackground = getColor(context, R.attr.panelColorBackground)
        val textColorPrimary = getColor(context, R.attr.textColorPrimary)
        val textColorSecondary = getColor(context, R.attr.textColorSecondary)
        val textColorTertiary = getColor(context, R.attr.textColorTertiary)
        val textColorPrimaryInverse = getColor(context, R.attr.textColorPrimaryInverse)
        val textColorSecondaryInverse = getColor(context, R.attr.textColorSecondaryInverse)
        val textColorTertiaryInverse = getColor(context, R.attr.textColorTertiaryInverse)
        val textColorOnAccent = getColor(context, R.attr.textColorOnAccent)
        val colorForeground = getColor(context, R.attr.colorForeground)
        val colorForegroundInverse = getColor(context, R.attr.colorForegroundInverse)
    }

    companion object {
        fun getColor(context: Context, attr: Int): Color {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            @ColorInt val color = ta.getColor(0, 0)
            ta.recycle()
            return Color(color)
        }
    }
}
