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

package com.android.systemui.communal.util

import android.view.Display
import android.view.WindowManagerGlobal
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * [DensityUtils] helps convert dp defined values to be consistent regardless of the set density.
 */
class DensityUtils {
    companion object {
        val Int.adjustedDp: Dp
            get() = this.dp * scalingAdjustment

        private val windowManagerService = WindowManagerGlobal.getWindowManagerService()
        val scalingAdjustment
            get() =
                windowManagerService?.let { wm ->
                    wm.getInitialDisplayDensity(Display.DEFAULT_DISPLAY).toFloat() /
                        wm.getBaseDisplayDensity(Display.DEFAULT_DISPLAY)
                } ?: 1F
    }
}
