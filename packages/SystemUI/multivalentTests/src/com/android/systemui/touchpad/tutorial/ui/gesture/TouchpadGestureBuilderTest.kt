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

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.DEFAULT_X
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.DEFAULT_Y
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TouchpadGestureBuilderTest : SysuiTestCase() {

    @Test
    fun threeFingerSwipeProducesCorrectEvents() {
        val events = ThreeFingerGesture.swipeRight()

        events.forEach {
            assertWithMessage("Event has three finger event parameters")
                .that(isThreeFingerTouchpadSwipe(it))
                .isTrue()
        }
        val actions = events.map { it.actionMasked }
        assertWithMessage("Events have expected action type")
            .that(actions)
            .containsExactly(
                ACTION_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_MOVE,
                ACTION_POINTER_UP,
                ACTION_POINTER_UP,
                ACTION_UP
            )
            .inOrder()
    }

    @Test
    fun fourFingerSwipeProducesCorrectEvents() {
        val events = FourFingerGesture.swipeUp()

        events.forEach {
            assertWithMessage("Event has four finger event parameters")
                .that(isFourFingerTouchpadSwipe(it))
                .isTrue()
        }
        val actions = events.map { it.actionMasked }
        assertWithMessage("Events have expected action type")
            .that(actions)
            .containsExactly(
                ACTION_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_POINTER_DOWN,
                ACTION_MOVE,
                ACTION_POINTER_UP,
                ACTION_POINTER_UP,
                ACTION_POINTER_UP,
                ACTION_UP
            )
            .inOrder()
    }

    @Test
    fun twoFingerSwipeProducesCorrectEvents() {
        val events = TwoFingerGesture.swipeLeft()

        events.forEach {
            assertWithMessage("Event has two finger event parameters")
                .that(isTwoFingerGesture(it))
                .isTrue()
        }
        val actions = events.map { it.actionMasked }
        assertWithMessage("Events have expected action type")
            .that(actions)
            .containsExactlyElementsIn(listOf(ACTION_DOWN, ACTION_MOVE, ACTION_UP))
            .inOrder()
    }

    private fun isTwoFingerGesture(event: MotionEvent): Boolean {
        return event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE &&
            event.getAxisValue(MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT) == 2f
    }

    @Test
    fun gestureBuilderProducesCorrectEventCoordinates() {
        val events =
            ThreeFingerGesture.createEvents {
                move(deltaX = 50f)
                move(deltaX = 100f)
            }
        val positions = events.map { it.x to it.y }
        assertWithMessage("Events have expected coordinates")
            .that(positions)
            .containsExactly(
                // down events
                DEFAULT_X to DEFAULT_Y,
                DEFAULT_X to DEFAULT_Y,
                DEFAULT_X to DEFAULT_Y,
                // move events
                DEFAULT_X + 50f to DEFAULT_Y,
                DEFAULT_X + 100f to DEFAULT_Y,
                // up events
                DEFAULT_X + 100f to DEFAULT_Y,
                DEFAULT_X + 100f to DEFAULT_Y,
                DEFAULT_X + 100f to DEFAULT_Y
            )
            .inOrder()
    }
}
