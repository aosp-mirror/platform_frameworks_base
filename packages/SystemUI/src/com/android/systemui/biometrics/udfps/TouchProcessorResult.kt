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

/** Contains all the possible returns types for [TouchProcessor.processTouch] */
sealed class TouchProcessorResult {

    /**
     * [event] whether a finger entered or left the sensor area. See [InteractionEvent].
     *
     * [pointerOnSensorId] pointerId fof the finger that's currently on the sensor. See
     * [MotionEvent.getPointerId]. If there is no finger on the sensor, the value is set to
     * [MotionEvent.INVALID_POINTER_ID].
     *
     * [touchData] relevant data from the MotionEvent, mapped to natural orientation and native
     * resolution. See [NormalizedTouchData].
     */
    data class ProcessedTouch(
        val event: InteractionEvent,
        val pointerOnSensorId: Int,
        val touchData: NormalizedTouchData
    ) : TouchProcessorResult()

    /** [reason] the reason for the failure. */
    data class Failure(val reason: String = "") : TouchProcessorResult()
}
