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
import androidx.annotation.ColorRes
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
 * This scheme contains the Material3 colors that are not available on
 * [androidx.compose.material3.MaterialTheme]. For other colors (e.g. primary), use
 * `MaterialTheme.colorScheme` instead.
 */
class AndroidColorScheme(val context: Context) {
    val primaryFixed = color(context, R.color.system_primary_fixed)
    val primaryFixedDim = color(context, R.color.system_primary_fixed_dim)
    val onPrimaryFixed = color(context, R.color.system_on_primary_fixed)
    val onPrimaryFixedVariant = color(context, R.color.system_on_primary_fixed_variant)
    val secondaryFixed = color(context, R.color.system_secondary_fixed)
    val secondaryFixedDim = color(context, R.color.system_secondary_fixed_dim)
    val onSecondaryFixed = color(context, R.color.system_on_secondary_fixed)
    val onSecondaryFixedVariant = color(context, R.color.system_on_secondary_fixed_variant)
    val tertiaryFixed = color(context, R.color.system_tertiary_fixed)
    val tertiaryFixedDim = color(context, R.color.system_tertiary_fixed_dim)
    val onTertiaryFixed = color(context, R.color.system_on_tertiary_fixed)
    val onTertiaryFixedVariant = color(context, R.color.system_on_tertiary_fixed_variant)

    companion object {
        internal fun color(context: Context, @ColorRes id: Int): Color {
            return Color(context.resources.getColor(id, context.theme))
        }
    }
}
