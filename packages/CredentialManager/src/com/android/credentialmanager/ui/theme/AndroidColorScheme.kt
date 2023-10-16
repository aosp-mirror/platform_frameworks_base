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

package com.android.credentialmanager.ui.theme

import android.annotation.ColorInt
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.android.internal.R

/** File copied from PlatformComposeCore. */

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
    val colorSurfaceBright = getColor(context, R.attr.materialColorSurfaceBright)
    val colorSurfaceContainerHigh = getColor(context, R.attr.materialColorSurfaceContainerHigh)
    val colorOutlineVariant = getColor(context, R.attr.materialColorOutlineVariant)
    val colorOnSurface = getColor(context, R.attr.materialColorOnSurface)
    val colorOnSurfaceVariant = getColor(context, R.attr.materialColorOnSurfaceVariant)

    companion object {
        fun getColor(context: Context, attr: Int): Color {
            val ta = context.obtainStyledAttributes(intArrayOf(attr))
            @ColorInt val color = ta.getColor(0, 0)
            ta.recycle()
            return Color(color)
        }
    }
}
