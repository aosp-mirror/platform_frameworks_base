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

package com.android.systemui.touchpad.tutorial.ui.gesture

import android.view.InputDevice
import android.view.MotionEvent

/**
 * Allows listening to touchpadGesture and calling onDone when gesture was triggered. Can have all
 * motion events passed to [onMotionEvent] and will filter touchpad events accordingly
 */
class TouchpadGestureHandler(
    private val gestureMonitor: TouchpadGestureMonitor,
) {

    fun onMotionEvent(event: MotionEvent): Boolean {
        // events from touchpad have SOURCE_MOUSE and not SOURCE_TOUCHPAD because of legacy reasons
        val isFromTouchpad =
            event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val buttonClick =
            event.actionMasked == MotionEvent.ACTION_DOWN &&
                event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)
        return if (isFromTouchpad && !buttonClick) {
            gestureMonitor.processTouchpadEvent(event)
            true
        } else {
            false
        }
    }
}
