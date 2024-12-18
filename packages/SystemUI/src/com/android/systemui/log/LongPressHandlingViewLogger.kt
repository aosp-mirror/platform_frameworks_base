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

package com.android.systemui.log

import com.android.systemui.log.core.LogLevel.DEBUG
import com.google.errorprone.annotations.CompileTimeConstant

data class LongPressHandlingViewLogger
constructor(
    private val logBuffer: LogBuffer,
    @CompileTimeConstant private val tag: String = "LongPressHandlingViewLogger"
) {
    fun schedulingLongPress(delay: Long) {
        logBuffer.log(
            tag,
            DEBUG,
            { long1 = delay },
            { "on MotionEvent.Down: scheduling long press activation after $long1 ms" }
        )
    }

    fun longPressTriggered() {
        logBuffer.log(tag, DEBUG, "long press event detected and dispatched")
    }

    fun motionEventCancelled() {
        logBuffer.log(tag, DEBUG, "Long press may be cancelled due to MotionEventModel.Cancel")
    }

    fun dispatchingSingleTap() {
        logBuffer.log(tag, DEBUG, "Dispatching single tap instead of long press")
    }

    fun onUpEvent(distanceMoved: Float, touchSlop: Int, gestureDuration: Long) {
        logBuffer.log(
            tag,
            DEBUG,
            {
                double1 = distanceMoved.toDouble()
                int1 = touchSlop
                long1 = gestureDuration
            },
            {
                "on MotionEvent.Up: distanceMoved: $double1, " +
                    "allowedTouchSlop: $int1, " +
                    "eventDuration: $long1"
            }
        )
    }

    fun cancelingLongPressDueToTouchSlop(distanceMoved: Float, allowedTouchSlop: Int) {
        logBuffer.log(
            tag,
            DEBUG,
            {
                double1 = distanceMoved.toDouble()
                int1 = allowedTouchSlop
            },
            {
                "on MotionEvent.Motion: May cancel long press due to movement: " +
                    "distanceMoved: $double1, " +
                    "allowedTouchSlop: $int1 "
            }
        )
    }
}
