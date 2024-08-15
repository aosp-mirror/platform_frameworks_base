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

import android.view.InputDevice.SOURCE_MOUSE
import android.view.InputDevice.SOURCE_TOUCHSCREEN
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_MOUSE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TouchpadGestureHandlerTest : SysuiTestCase() {

    private var gestureState = NOT_STARTED
    private val handler =
        TouchpadGestureHandler(
            BackGestureMonitor(
                gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt(),
                gestureStateChangedCallback = { gestureState = it }
            )
        )

    @Test
    fun handlesEventsFromTouchpad() {
        val event = downEvent(source = SOURCE_MOUSE, toolType = TOOL_TYPE_FINGER)
        val eventHandled = handler.onMotionEvent(event)
        assertThat(eventHandled).isTrue()
    }

    @Test
    fun ignoresEventsFromMouse() {
        val event = downEvent(source = SOURCE_MOUSE, toolType = TOOL_TYPE_MOUSE)
        val eventHandled = handler.onMotionEvent(event)
        assertThat(eventHandled).isFalse()
    }

    @Test
    fun ignoresEventsFromTouch() {
        val event = downEvent(source = SOURCE_TOUCHSCREEN, toolType = TOOL_TYPE_FINGER)
        val eventHandled = handler.onMotionEvent(event)
        assertThat(eventHandled).isFalse()
    }

    @Test
    fun ignoresButtonClicksFromTouchpad() {
        val event = downEvent(source = SOURCE_MOUSE, toolType = TOOL_TYPE_FINGER)
        event.buttonState = MotionEvent.BUTTON_PRIMARY
        val eventHandled = handler.onMotionEvent(event)
        assertThat(eventHandled).isFalse()
    }

    private fun downEvent(source: Int, toolType: Int) =
        motionEvent(action = ACTION_DOWN, x = 0f, y = 0f, source = source, toolType = toolType)

    @Test
    fun triggersGestureDoneForThreeFingerGesture() {
        backGestureEvents().forEach { handler.onMotionEvent(it) }

        assertThat(gestureState).isEqualTo(FINISHED)
    }

    private fun backGestureEvents(): List<MotionEvent> {
        return ThreeFingerGesture.createEvents {
            move(deltaX = SWIPE_DISTANCE / 4)
            move(deltaX = SWIPE_DISTANCE / 2)
            move(deltaX = SWIPE_DISTANCE)
        }
    }
}
