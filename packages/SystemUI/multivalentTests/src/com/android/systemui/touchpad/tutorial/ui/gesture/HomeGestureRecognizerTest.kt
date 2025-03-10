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
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.Finished
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NotStarted
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.android.systemui.touchpad.ui.gesture.fakeVelocityTracker
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeGestureRecognizerTest : SysuiTestCase() {

    companion object {
        const val THRESHOLD_VELOCITY_PX_PER_MS = 1f
        const val SLOW = THRESHOLD_VELOCITY_PX_PER_MS - 0.01f
        const val FAST = THRESHOLD_VELOCITY_PX_PER_MS + 0.01f
    }

    private var gestureState: GestureState = GestureState.NotStarted
    private var velocityTracker = testKosmos().fakeVelocityTracker
    private val gestureRecognizer =
        HomeGestureRecognizer(
            gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt(),
            velocityThresholdPxPerMs = THRESHOLD_VELOCITY_PX_PER_MS,
            velocityTracker = velocityTracker,
        )

    @Before
    fun before() {
        velocityTracker.setVelocity(Velocity(FAST))
        gestureRecognizer.addGestureStateCallback { gestureState = it }
    }

    @Test
    fun triggersGestureFinishedForThreeFingerGestureUp() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = Finished)
    }

    @Test
    fun doesntTriggerGestureFinished_onGestureSpeedTooSlow() {
        velocityTracker.setVelocity(Velocity(SLOW))
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = NotStarted)
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
        assertProgressWhileMovingFingers(deltaY = -SWIPE_DISTANCE / 2, expectedProgress = 0.5f)
        assertProgressWhileMovingFingers(deltaY = -SWIPE_DISTANCE, expectedProgress = 1f)
    }

    private fun assertProgressWhileMovingFingers(deltaY: Float, expectedProgress: Float) {
        assertStateAfterEvents(
            events = ThreeFingerGesture.eventsForGestureInProgress { move(deltaY = deltaY) },
            expectedState = InProgress(progress = expectedProgress),
        )
    }

    @Test
    fun triggeredProgressIsBetweenZeroAndOne() {
        // going in the wrong direction
        assertProgressWhileMovingFingers(deltaY = SWIPE_DISTANCE / 2, expectedProgress = 0f)
        // going further than required distance
        assertProgressWhileMovingFingers(deltaY = -SWIPE_DISTANCE * 2, expectedProgress = 1f)
    }

    @Test
    fun doesntTriggerGestureFinished_onGestureDistanceTooShort() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.swipeUp(distancePx = SWIPE_DISTANCE / 2),
            expectedState = NotStarted,
        )
    }

    @Test
    fun doesntTriggerGestureFinished_onThreeFingersSwipeInOtherDirections() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeDown(), expectedState = NotStarted)
        assertStateAfterEvents(events = ThreeFingerGesture.swipeLeft(), expectedState = NotStarted)
        assertStateAfterEvents(events = ThreeFingerGesture.swipeRight(), expectedState = NotStarted)
    }

    @Test
    fun doesntTriggerGestureFinished_onTwoFingersSwipe() {
        assertStateAfterEvents(events = TwoFingerGesture.swipeUp(), expectedState = NotStarted)
    }

    @Test
    fun doesntTriggerGestureFinished_onFourFingersSwipe() {
        assertStateAfterEvents(events = FourFingerGesture.swipeUp(), expectedState = NotStarted)
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, expectedState: GestureState) {
        events.forEach { gestureRecognizer.accept(it) }
        assertThat(gestureState).isEqualTo(expectedState)
    }
}
