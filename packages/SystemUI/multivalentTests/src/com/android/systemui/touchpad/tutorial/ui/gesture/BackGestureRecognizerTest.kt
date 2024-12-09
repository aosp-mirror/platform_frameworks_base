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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureDirection.LEFT
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureDirection.RIGHT
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NotStarted
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BackGestureRecognizerTest : SysuiTestCase() {

    private var gestureState: GestureState = NotStarted
    private val gestureRecognizer =
        BackGestureRecognizer(gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt())

    @Before
    fun before() {
        gestureRecognizer.addGestureStateCallback { gestureState = it }
    }

    @Test
    fun triggersProgressRelativeToDistanceWhenSwipingLeft() {
        assertProgressWhileMovingFingers(
            deltaX = -SWIPE_DISTANCE / 2,
            expected = InProgress(progress = 0.5f, direction = LEFT),
        )
        assertProgressWhileMovingFingers(
            deltaX = -SWIPE_DISTANCE,
            expected = InProgress(progress = 1f, direction = LEFT),
        )
    }

    @Test
    fun triggersProgressRelativeToDistanceWhenSwipingRight() {
        assertProgressWhileMovingFingers(
            deltaX = SWIPE_DISTANCE / 2,
            expected = InProgress(progress = 0.5f, direction = RIGHT),
        )
        assertProgressWhileMovingFingers(
            deltaX = SWIPE_DISTANCE,
            expected = InProgress(progress = 1f, direction = RIGHT),
        )
    }

    @Test
    fun triggeredProgressIsNoBiggerThanOne() {
        assertProgressWhileMovingFingers(
            deltaX = SWIPE_DISTANCE * 2,
            expected = InProgress(progress = 1f, direction = RIGHT),
        )
        assertProgressWhileMovingFingers(
            deltaX = -SWIPE_DISTANCE * 2,
            expected = InProgress(progress = 1f, direction = LEFT),
        )
    }

    private fun assertProgressWhileMovingFingers(deltaX: Float, expected: InProgress) {
        assertStateAfterEvents(
            events = ThreeFingerGesture.eventsForGestureInProgress { move(deltaX = deltaX) },
            expectedState = expected,
        )
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, expectedState: GestureState) {
        events.forEach { gestureRecognizer.accept(it) }
        assertThat(gestureState).isEqualTo(expectedState)
    }
}
