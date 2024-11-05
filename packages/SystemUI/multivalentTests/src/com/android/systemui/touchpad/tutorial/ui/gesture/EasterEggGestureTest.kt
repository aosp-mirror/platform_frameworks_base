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
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.SWIPE_DISTANCE
import com.google.common.truth.Truth.assertThat
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class EasterEggGestureTest : SysuiTestCase() {

    private data class Point(val x: Float, val y: Float)

    private var triggered = false
    private val handler =
        TouchpadGestureHandler(
            BackGestureRecognizer(gestureDistanceThresholdPx = SWIPE_DISTANCE.toInt()),
            EasterEggGestureMonitor(callback = { triggered = true }),
        )

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
        events.forEach { handler.onMotionEvent(it) }
        assertThat(triggered).isEqualTo(wasTriggered)
    }

    private fun assertStateAfterTwoFingerGesture(gesturePath: List<Point>, wasTriggered: Boolean) {
        val events =
            TwoFingerGesture.eventsForFullGesture { gesturePath.forEach { (x, y) -> move(x, y) } }
        assertStateAfterEvents(events = events, wasTriggered = wasTriggered)
    }

    /**
     * Generates list of points that would make up clockwise circular motion with given [radius].
     * [circlesCount] determines how many full circles gesture should perform. [radiusNoiseFraction]
     * can introduce noise to mimic real-world gesture which is not perfect - shape will be still
     * circular but radius at any given point can be deviate from given radius by
     * [radiusNoiseFraction].
     */
    private fun generateCircularGesturePoints(
        circlesCount: Int,
        radiusNoiseFraction: Double? = null,
        radius: Float = 100f,
    ): List<Point> {
        val pointsPerCircle = 50
        val angleStep = 360 / pointsPerCircle
        val angleBuffer = 20 // buffer to make sure we're doing a bit more than 360 degree
        val totalAngle = circlesCount * (360 + angleBuffer)
        // Because all gestures in tests should start at (DEFAULT_X, DEFAULT_Y) we need to shift
        // circle center x coordinate by radius
        val centerX = -radius
        val centerY = 0f

        val events = mutableListOf<Point>()
        val randomNoise: (Double) -> Double =
            if (radiusNoiseFraction == null) {
                { 0.0 }
            } else {
                { radianAngle -> sin(radianAngle * 2) * radiusNoiseFraction }
            }

        var currentAngle = 0f
        // as cos(0) == 1 and sin(0) == 0 we start gesture at position of (radius, 0) and go
        // clockwise - first Y increases and X decreases
        while (currentAngle < totalAngle) {
            val radianAngle = Math.toRadians(currentAngle.toDouble())
            val radiusWithNoise = radius * (1 + randomNoise(radianAngle).toFloat())
            val x = centerX + radiusWithNoise * cos(radianAngle).toFloat()
            val y = centerY + radiusWithNoise * sin(radianAngle).toFloat()
            events.add(Point(x, y))
            currentAngle += angleStep
        }
        return events
    }
}
