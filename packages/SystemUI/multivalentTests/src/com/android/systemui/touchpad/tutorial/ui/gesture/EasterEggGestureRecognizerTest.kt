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

import android.graphics.PointF
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.touchpad.tutorial.ui.gesture.EasterEggGesture.generateCircularGesturePoints
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EasterEggGestureRecognizerTest : SysuiTestCase() {

    private var triggered = false
    private val gestureRecognizer = EasterEggGestureRecognizer()

    @Before
    fun setup() {
        gestureRecognizer.addGestureStateCallback { triggered = it == GestureState.Finished }
    }

    @Test
    fun easterEggTriggeredAfterThreeCircles() {
        assertStateAfterTwoFingerGesture(
            gesturePath = generateCircularGesturePoints(circlesCount = 3),
            wasTriggered = true,
        )
    }

    @Test
    fun easterEggTriggeredAfterThreeImperfectCircles() {
        assertStateAfterTwoFingerGesture(
            gesturePath =
                generateCircularGesturePoints(circlesCount = 3, radiusNoiseFraction = 0.2),
            wasTriggered = true,
        )
    }

    @Test
    fun easterEggTriggeredAfterFiveCircles() {
        assertStateAfterTwoFingerGesture(
            gesturePath = generateCircularGesturePoints(circlesCount = 5),
            wasTriggered = true,
        )
    }

    @Test
    fun easterEggNotTriggeredAfterTwoCircles() {
        assertStateAfterTwoFingerGesture(
            gesturePath = generateCircularGesturePoints(circlesCount = 2),
            wasTriggered = false,
        )
    }

    @Test
    fun easterEggNotTriggeredAfterVariousSwipes() {
        val allSwipeGestures =
            listOf(
                // two finger gestures
                TwoFingerGesture.swipeUp(),
                TwoFingerGesture.swipeDown(),
                TwoFingerGesture.swipeLeft(),
                TwoFingerGesture.swipeRight(),
                // three finger gestures
                ThreeFingerGesture.swipeUp(),
                ThreeFingerGesture.swipeDown(),
                ThreeFingerGesture.swipeLeft(),
                ThreeFingerGesture.swipeRight(),
                // four finger gestures
                FourFingerGesture.swipeUp(),
                FourFingerGesture.swipeDown(),
                FourFingerGesture.swipeLeft(),
                FourFingerGesture.swipeRight(),
            )
        allSwipeGestures.forEach { gesture ->
            assertStateAfterEvents(events = gesture, wasTriggered = false)
        }
    }

    private fun assertStateAfterEvents(events: List<MotionEvent>, wasTriggered: Boolean) {
        events.forEach { gestureRecognizer.accept(it) }
        assertThat(triggered).isEqualTo(wasTriggered)
    }

    private fun assertStateAfterTwoFingerGesture(gesturePath: List<PointF>, wasTriggered: Boolean) {
        val events =
            TwoFingerGesture.eventsForFullGesture { gesturePath.forEach { p -> move(p.x, p.y) } }
        assertStateAfterEvents(events = events, wasTriggered = wasTriggered)
    }
}
