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
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.BUTTON_PRIMARY
import com.android.systemui.touchpad.tutorial.ui.viewmodel.GestureRecognizerAdapter

object TouchpadEventsFilter {

    fun isTouchpadAndNonClickEvent(event: MotionEvent): Boolean {
        // events from touchpad have SOURCE_MOUSE and not SOURCE_TOUCHPAD because of legacy reasons
        val isFromTouchpad =
            event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val isButtonClicked =
            event.actionMasked == ACTION_DOWN && event.isButtonPressed(BUTTON_PRIMARY)
        return isFromTouchpad && !isButtonClicked
    }
}

fun GestureRecognizer.handleTouchpadMotionEvent(event: MotionEvent): Boolean {
    return if (TouchpadEventsFilter.isTouchpadAndNonClickEvent(event)) {
        this.accept(event)
        true
    } else {
        false
    }
}

fun GestureRecognizerAdapter.handleTouchpadMotionEvent(event: MotionEvent): Boolean {
    return if (TouchpadEventsFilter.isTouchpadAndNonClickEvent(event)) {
        this.accept(event)
        true
    } else {
        false
    }
}
