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
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.IN_PROGRESS
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class HomeGestureMonitorTest : SysuiTestCase() {

    private var gestureState = NOT_STARTED
    private val gestureMonitor =
        HomeGestureMonitor(
            gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt(),
            gestureStateChangedCallback = { gestureState = it }
        )

    @Test
    fun triggersGestureFinishedForThreeFingerGestureUp() {
        assertStateAfterEvents(events = ThreeFingerGesture.swipeUp(), expectedState = FINISHED)
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
