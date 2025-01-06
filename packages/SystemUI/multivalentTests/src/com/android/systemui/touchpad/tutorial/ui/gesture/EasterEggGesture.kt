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
import kotlin.math.cos
import kotlin.math.sin

/** Test helper to generate circular gestures or full EasterEgg gesture */
object EasterEggGesture {

    fun motionEventsForGesture(): List<MotionEvent> {
        val gesturePath = generateCircularGesturePoints(circlesCount = 3)
        val events =
            TwoFingerGesture.eventsForFullGesture { gesturePath.forEach { p -> move(p.x, p.y) } }
        return events
    }

    /**
     * Generates list of points that would make up clockwise circular motion with given [radius].
     * [circlesCount] determines how many full circles gesture should perform. [radiusNoiseFraction]
     * can introduce noise to mimic real-world gesture which is not perfect - shape will be still
     * circular but radius at any given point can be deviate from given radius by
     * [radiusNoiseFraction].
     */
    fun generateCircularGesturePoints(
        circlesCount: Int,
        radiusNoiseFraction: Double? = null,
        radius: Float = 100f,
    ): List<PointF> {
        val pointsPerCircle = 50
        val angleStep = 360 / pointsPerCircle
        val angleBuffer = 20 // buffer to make sure we're doing a bit more than 360 degree
        val totalAngle = circlesCount * (360 + angleBuffer)
        // Because all gestures in tests should start at (DEFAULT_X, DEFAULT_Y) we need to shift
        // circle center x coordinate by radius
        val centerX = -radius
        val centerY = 0f

        val randomNoise: (Double) -> Double =
            if (radiusNoiseFraction == null) {
                { 0.0 }
            } else {
                { radianAngle -> sin(radianAngle * 2) * radiusNoiseFraction }
            }

        val events = mutableListOf<PointF>()
        var currentAngle = 0f
        // as cos(0) == 1 and sin(0) == 0 we start gesture at position of (radius, 0) and go
        // clockwise - first Y increases and X decreases
        while (currentAngle < totalAngle) {
            val radianAngle = Math.toRadians(currentAngle.toDouble())
            val radiusWithNoise = radius * (1 + randomNoise(radianAngle).toFloat())
            val x = centerX + radiusWithNoise * cos(radianAngle).toFloat()
            val y = centerY + radiusWithNoise * sin(radianAngle).toFloat()
            events.add(PointF(x, y))
            currentAngle += angleStep
        }
        return events
    }
}
