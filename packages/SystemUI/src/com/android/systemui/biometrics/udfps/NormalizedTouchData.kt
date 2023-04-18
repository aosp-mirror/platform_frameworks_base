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

import android.graphics.Rect
import android.view.MotionEvent

/** Touch data in natural orientation and native resolution. */
data class NormalizedTouchData(

    /**
     * Value obtained from [MotionEvent.getPointerId], or [MotionEvent.INVALID_POINTER_ID] if the ID
     * is not available.
     */
    val pointerId: Int = MotionEvent.INVALID_POINTER_ID,

    /** [MotionEvent.getRawX] mapped to natural orientation and native resolution. */
    val x: Float = 0f,

    /** [MotionEvent.getRawY] mapped to natural orientation and native resolution. */
    val y: Float = 0f,

    /** [MotionEvent.getTouchMinor] mapped to natural orientation and native resolution. */
    val minor: Float = 0f,

    /** [MotionEvent.getTouchMajor] mapped to natural orientation and native resolution. */
    val major: Float = 0f,

    /** [MotionEvent.getOrientation] mapped to natural orientation. */
    val orientation: Float = 0f,

    /** [MotionEvent.getEventTime]. */
    val time: Long = 0,

    /** [MotionEvent.getDownTime]. */
    val gestureStart: Long = 0,
) {

    /**
     * [nativeSensorBounds] contains the location and dimensions of the sensor area in native
     * resolution and natural orientation.
     *
     * Returns whether the coordinates of the given pointer are within the sensor's bounding box.
     */
    fun isWithinSensor(nativeSensorBounds: Rect): Boolean {
        return nativeSensorBounds.left <= x &&
            nativeSensorBounds.right >= x &&
            nativeSensorBounds.top <= y &&
            nativeSensorBounds.bottom >= y
    }
}
