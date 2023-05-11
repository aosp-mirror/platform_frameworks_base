/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.common.ui.view

import android.util.MathUtils
import android.view.MotionEvent

/**
 * Returns the distance from the raw position of this [MotionEvent] and the given coordinates.
 * Because this is all expected to be in the coordinate space of the display and not the view,
 * applying mutations to the view (such as scaling animations) does not affect the distance
 * measured.
 * @param xOnDisplay the x coordinate relative to the display
 * @param yOnDisplay the y coordinate relative to the display
 * @return distance from the raw position of this [MotionEvent] and the given coordinates
 */
fun MotionEvent.rawDistanceFrom(
    xOnDisplay: Float,
    yOnDisplay: Float,
): Float {
    return MathUtils.dist(this.rawX, this.rawY, xOnDisplay, yOnDisplay)
}
