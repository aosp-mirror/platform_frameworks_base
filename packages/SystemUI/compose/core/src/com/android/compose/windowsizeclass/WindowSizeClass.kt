/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.compose.windowsizeclass

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toComposeRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.WindowMetricsCalculator

val LocalWindowSizeClass =
    staticCompositionLocalOf<WindowSizeClass> {
        throw IllegalStateException(
            "No WindowSizeClass configured. Make sure to use LocalWindowSizeClass in a Composable" +
                " surrounded by a PlatformTheme {}."
        )
    }

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun calculateWindowSizeClass(): WindowSizeClass {
    // Observe view configuration changes and recalculate the size class on each change.
    LocalConfiguration.current
    val density = LocalDensity.current
    val context = LocalContext.current
    val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
    val size = with(density) { metrics.bounds.toComposeRect().size.toDpSize() }
    return WindowSizeClass.calculateFromSize(size)
}
