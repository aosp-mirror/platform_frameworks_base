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

package com.android.systemui.biometrics.udfps

import android.graphics.RectF
import android.view.MotionEvent
import com.android.systemui.biometrics.UdfpsOverlayParams

/** Touch data in natural orientation and native resolution. */
data class NormalizedTouchData(

    /**
     * Value obtained from [MotionEvent.getPointerId], or [MotionEvent.INVALID_POINTER_ID] if the ID
     * is not available.
     */
    val pointerId: Int,

    /** [MotionEvent.getRawX] mapped to natural orientation and native resolution. */
    val x: Float,

    /** [MotionEvent.getRawY] mapped to natural orientation and native resolution. */
    val y: Float,

    /** [MotionEvent.getTouchMinor] mapped to natural orientation and native resolution. */
    val minor: Float,

    /** [MotionEvent.getTouchMajor] mapped to natural orientation and native resolution. */
    val major: Float,

    /** [MotionEvent.getOrientation] mapped to natural orientation. */
    val orientation: Float,

    /** [MotionEvent.getEventTime]. */
    val time: Long,

    /** [MotionEvent.getDownTime]. */
    val gestureStart: Long,
) {

    /**
     * [overlayParams] contains the location and dimensions of the sensor area, as well as the scale
     * factor and orientation of the overlay. See [UdfpsOverlayParams].
     *
     * Returns whether the given pointer is within the sensor's bounding box.
     */
    fun isWithinSensor(overlayParams: UdfpsOverlayParams): Boolean {
        val r = RectF(overlayParams.sensorBounds).apply { scale(1f / overlayParams.scaleFactor) }
        return r.left <= x && r.right >= x && r.top <= y && r.bottom >= y
    }
}
