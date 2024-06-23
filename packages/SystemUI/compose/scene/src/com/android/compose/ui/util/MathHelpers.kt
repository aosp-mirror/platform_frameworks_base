/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.compose.ui.util

import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.lerp
import com.android.compose.animation.scene.Scale

/** Linearly interpolate between [start] and [stop] with [fraction] fraction between them. */
fun lerp(start: IntSize, stop: IntSize, fraction: Float): IntSize {
    return IntSize(
        lerp(start.width, stop.width, fraction),
        lerp(start.height, stop.height, fraction)
    )
}

/** Linearly interpolate between [start] and [stop] with [fraction] fraction between them. */
fun lerp(start: Scale, stop: Scale, fraction: Float): Scale {
    val pivot =
        when {
            start.pivot.isSpecified && stop.pivot.isSpecified ->
                lerp(start.pivot, stop.pivot, fraction)
            start.pivot.isSpecified -> start.pivot
            else -> stop.pivot
        }
    return Scale(
        lerp(start.scaleX, stop.scaleX, fraction),
        lerp(start.scaleY, stop.scaleY, fraction),
        pivot
    )
}
