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
class AndroidColorScheme internal constructor(context: Context) {
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

    companion object {
        fun getColor(context: Context, attr: Int): Color {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            @ColorInt val color = ta.getColor(0, 0)
            ta.recycle()
            return Color(color)
        }
    }
}
