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
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.Finished
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
    fun triggersGestureFinishedForThreeFingerGestureRight() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeRight(), expectedState = Finished)
    }

    @Test
    fun triggersGestureFinishedForThreeFingerGestureLeft() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeLeft(), expectedState = Finished)
    }

    @Test
    fun triggersGestureProgressForThreeFingerGestureStarted() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.startEvents(x = 0f, y = 0f),
            expectedState = InProgress(),
        )
    }

    @Test
    fun triggersProgressRelativeToDistance() {
        assertProgressWhileMovingFingers(deltaX = -SWIPE_DISTANCE / 2, expectedProgress = 0.5f)
        assertProgressWhileMovingFingers(deltaX = SWIPE_DISTANCE / 2, expectedProgress = 0.5f)
        assertProgressWhileMovingFingers(deltaX = -SWIPE_DISTANCE, expectedProgress = 1f)
        assertProgressWhileMovingFingers(deltaX = SWIPE_DISTANCE, expectedProgress = 1f)
    }

    private fun assertProgressWhileMovingFingers(deltaX: Float, expectedProgress: Float) {
        assertStateAfterEvents(
            events = ThreeFingerGesture.eventsForGestureInProgress { move(deltaX = deltaX) },
            expectedState = InProgress(progress = expectedProgress),
        )
    }

    @Test
    fun triggeredProgressIsNoBiggerThanOne() {
        assertProgressWhileMovingFingers(deltaX = SWIPE_DISTANCE * 2, expectedProgress = 1f)
    }

    @Test
    fun doesntTriggerGestureFinished_onGestureDistanceTooShort() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.swipeLeft(distancePx = SWIPE_DISTANCE / 2),
            expectedState = NotStarted,
        )
    }

    @Test
    fun doesntTriggerGestureFinished_onThreeFingersSwipeInOtherDirections() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = NotStarted)
        assertStateAfterEvents(events = ThreeFingerGesture.swipeDown(), expectedState = NotStarted)
    }

    @Test
    fun doesntTriggerGestureFinished_onTwoFingersSwipe() {
        assertStateAfterEvents(events = TwoFingerGesture.swipeRight(), expectedState = NotStarted)
    }

    @Test
    fun doesntTriggerGestureFinished_onFourFingersSwipe() {
        assertStateAfterEvents(events = FourFingerGesture.swipeRight(), expectedState = NotStarted)
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, expectedState: GestureState) {
        events.forEach { gestureRecognizer.accept(it) }
        assertThat(gestureState).isEqualTo(expectedState)
    }
}
