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
import android.view.MotionEvent.ACTION_HOVER_ENTER
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT
import android.view.MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_MOUSE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.TouchpadGesture.BACK
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TouchpadGestureHandlerTest : SysuiTestCase() {

    private var gestureDone = false
    private val handler = TouchpadGestureHandler(BACK, SWIPE_DISTANCE) { gestureDone = true }

    companion object {
        const val SWIPE_DISTANCE = 100
    }

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

        assertThat(gestureDone).isTrue()
    }

    private fun backGestureEvents(): List<MotionEvent> {
        // list of motion events read from device while doing back gesture
        val y = 100f
        return listOf(
            touchpadEvent(ACTION_HOVER_ENTER, x = 759f, y = y, pointerCount = 1),
            threeFingerTouchpadEvent(ACTION_DOWN, x = 759f, y = y, pointerCount = 1),
            threeFingerTouchpadEvent(ACTION_POINTER_DOWN, x = 759f, y = y, pointerCount = 2),
            threeFingerTouchpadEvent(ACTION_POINTER_DOWN, x = 759f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_MOVE, x = 767f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_MOVE, x = 785f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_MOVE, x = 814f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_MOVE, x = 848f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_MOVE, x = 943f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_POINTER_UP, x = 943f, y = y, pointerCount = 3),
            threeFingerTouchpadEvent(ACTION_POINTER_UP, x = 943f, y = y, pointerCount = 2),
            threeFingerTouchpadEvent(ACTION_UP, x = 943f, y = y, pointerCount = 1)
        )
    }

    private fun threeFingerTouchpadEvent(
        action: Int,
        x: Float,
        y: Float,
        pointerCount: Int
    ): MotionEvent {
        return touchpadEvent(
            action = action,
            x = x,
            y = y,
            pointerCount = pointerCount,
            classification = CLASSIFICATION_MULTI_FINGER_SWIPE,
            axisValues = mapOf(AXIS_GESTURE_SWIPE_FINGER_COUNT to 3f)
        )
    }
}
