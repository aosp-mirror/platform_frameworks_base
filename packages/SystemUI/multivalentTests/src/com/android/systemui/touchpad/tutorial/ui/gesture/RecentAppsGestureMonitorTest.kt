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
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.IN_PROGRESS
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class RecentAppsGestureMonitorTest : SysuiTestCase() {

    companion object {
        const val THRESHOLD_VELOCITY_PX_PER_MS = 0.1f
        // multiply by 1000 to get px/ms instead of px/s which is unit used by velocity tracker
        const val SLOW = THRESHOLD_VELOCITY_PX_PER_MS * 1000 - 1
        const val FAST = THRESHOLD_VELOCITY_PX_PER_MS * 1000 + 1
    }

    private var gestureState = NOT_STARTED
    private val velocityTracker =
        mock<VelocityTracker1D> {
            // by default return correct speed for the gesture - as if pointer is slowing down
            on { calculateVelocity() } doReturn SLOW
        }
    private val gestureMonitor =
        RecentAppsGestureMonitor(
            gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt(),
            gestureStateChangedCallback = { gestureState = it },
            velocityThresholdPxPerMs = THRESHOLD_VELOCITY_PX_PER_MS,
            velocityTracker = velocityTracker
        )

    @Test
    fun triggersGestureFinishedForThreeFingerGestureUp() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = FINISHED)
    }

    @Test
    fun doesntTriggerGestureFinished_onGestureSpeedTooHigh() {
        whenever(velocityTracker.calculateVelocity()).thenReturn(FAST)
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = NOT_STARTED)
    }

    @Test
    fun triggersGestureProgressForThreeFingerGestureStarted() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.startEvents(x = 0f, y = 0f),
            expectedState = IN_PROGRESS
        )
    }

    @Test
    fun doesntTriggerGestureFinished_onGestureDistanceTooShort() {
        assertStateAfterEvents(
            events = ThreeFingerGesture.swipeUp(distancePx = SWIPE_DISTANCE / 2),
            expectedState = NOT_STARTED
        )
    }

    @Test
    fun doesntTriggerGestureFinished_onThreeFingersSwipeInOtherDirections() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeDown(), expectedState = NOT_STARTED)
        assertStateAfterEvents(events = ThreeFingerGesture.swipeLeft(), expectedState = NOT_STARTED)
        assertStateAfterEvents(
            events = ThreeFingerGesture.swipeRight(),
            expectedState = NOT_STARTED
        )
    }

    @Test
    fun doesntTriggerGestureFinished_onTwoFingersSwipe() {
        assertStateAfterEvents(events = TwoFingerGesture.swipeUp(), expectedState = NOT_STARTED)
    }

    @Test
    fun doesntTriggerGestureFinished_onFourFingersSwipe() {
        assertStateAfterEvents(events = FourFingerGesture.swipeUp(), expectedState = NOT_STARTED)
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, expectedState: GestureState) {
        events.forEach { gestureMonitor.processTouchpadEvent(it) }
        assertThat(gestureState).isEqualTo(expectedState)
    }
}
