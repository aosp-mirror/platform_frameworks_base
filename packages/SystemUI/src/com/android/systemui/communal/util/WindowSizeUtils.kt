/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.util

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.window.layout.WindowMetricsCalculator

/**
 * [WindowSizeUtils] defines viewport breakpoints that helps create responsive mobile layout.
 *
 * @see https://developer.android.com/develop/ui/views/layout/use-window-size-classes
 */
object WindowSizeUtils {
    /** Compact screen width breakpoint. */
    val COMPACT_WIDTH = 600.dp
    /** Medium screen width breakpoint. */
    val MEDIUM_WIDTH = 840.dp
    /** Compact screen height breakpoint. */
    val COMPACT_HEIGHT = 480.dp
    /** Expanded screen height breakpoint. */
    val EXPANDED_HEIGHT = 900.dp

    /** Whether the window size is compact, which reflects most mobile sizes in portrait. */
    @JvmStatic
    fun isCompactWindowSize(context: Context): Boolean {
        val metrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
        val width = metrics.bounds.width()
        return width / metrics.density < COMPACT_WIDTH.value
    }
}
