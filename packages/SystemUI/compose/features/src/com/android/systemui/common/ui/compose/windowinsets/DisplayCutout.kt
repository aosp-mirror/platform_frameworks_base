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

package com.android.systemui.common.ui.compose.windowinsets

import android.view.DisplayCutout as ViewDisplayCutout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Represents the global position of the bounds for the display cutout for this display */
data class DisplayCutout(
    val left: Dp = 0.dp,
    val top: Dp = 0.dp,
    val right: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val location: CutoutLocation = CutoutLocation.NONE,
    /**
     * The original `DisplayCutout` for the `View` world; only use this when feeding it back to a
     * `View`.
     */
    val viewDisplayCutoutKeyguardStatusBarView: ViewDisplayCutout? = null,
) {
    fun width() = abs(right.value - left.value).dp
}

enum class CutoutLocation {
    NONE,
    CENTER,
    LEFT,
    RIGHT,
}
