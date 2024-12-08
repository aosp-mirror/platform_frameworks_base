/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.common

import android.content.res.Resources
import android.graphics.RectF
import android.util.DisplayMetrics
import android.view.DisplayInfo
import org.mockito.Mockito.spy

/** Utility class for tests of [DesktopModeWindowDecorViewModel] */
object MultiDisplayTestUtil {
    // We have two displays, display#1 is placed on middle top of display#0:
    //   +---+
    //   | 1 |
    // +-+---+-+
    // |   0   |
    // +-------+
    val DISPLAY_GLOBAL_BOUNDS_0 = RectF(0f, 0f, 1200f, 800f)
    val DISPLAY_GLOBAL_BOUNDS_1 = RectF(100f, -1000f, 1100f, 0f)
    val DISPLAY_DPI_0 = DisplayMetrics.DENSITY_DEFAULT
    val DISPLAY_DPI_1 = DisplayMetrics.DENSITY_DEFAULT * 2

    fun createSpyDisplayLayout(globalBounds: RectF, dpi: Int, resources: Resources): DisplayLayout {
        val displayInfo = DisplayInfo()
        displayInfo.logicalDensityDpi = dpi
        val displayLayout = spy(DisplayLayout(displayInfo, resources, true, true))
        displayLayout.setGlobalBoundsDp(globalBounds)
        return displayLayout
    }
}
