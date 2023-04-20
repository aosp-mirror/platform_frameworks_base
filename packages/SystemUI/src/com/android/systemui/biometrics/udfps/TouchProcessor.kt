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

import android.view.MotionEvent
import com.android.systemui.biometrics.UdfpsOverlayParams

/**
 * Determines whether a finger entered or left the area of the under-display fingerprint sensor
 * (UDFPS). Maps the touch information from a [MotionEvent] to the orientation and scale independent
 * [NormalizedTouchData].
 */
interface TouchProcessor {

    /**
     * [event] touch event to be processed.
     *
     * [previousPointerOnSensorId] pointerId for the finger that was on the sensor prior to this
     * event. See [MotionEvent.getPointerId]. If there was no finger on the sensor, this should be
     * set to [MotionEvent.INVALID_POINTER_ID].
     *
     * [overlayParams] contains the location and dimensions of the sensor area, as well as the scale
     * factor and orientation of the overlay. See [UdfpsOverlayParams].
     *
     * Returns [TouchProcessorResult.ProcessedTouch] on success, and [TouchProcessorResult.Failure]
     * on failure.
     */
    fun processTouch(
        event: MotionEvent,
        previousPointerOnSensorId: Int,
        overlayParams: UdfpsOverlayParams,
    ): TouchProcessorResult
}
