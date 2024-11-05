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
import com.android.systemui.touchpad.tutorial.ui.gesture.EasterEggGestureMonitor.Companion.CIRCLES_COUNT_THRESHOLD
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Monitor recognizing easter egg gesture, that is at least [CIRCLES_COUNT_THRESHOLD] circles
 * clockwise within one gesture. It tries to be on the safer side of not triggering gesture if we're
 * not sure if full circle was done.
 */
class EasterEggGestureMonitor(private val callback: () -> Unit) {

    private var last: Point = Point(0f, 0f)
    private var cumulativeAngle: Float = 0f
    private var lastAngle: Float? = null
    private var circleCount: Int = 0

    private class Point(val x: Float, val y: Float)

    private val points = mutableListOf<Point>()

    fun processTouchpadEvent(event: MotionEvent) {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                reset()
                last = Point(event.x, event.y)
                points.add(Point(event.x, event.y))
            }
            MotionEvent.ACTION_MOVE -> {
                val current = Point(event.x, event.y)
                points.add(current)

                if (distanceBetween(last, current) > MIN_MOTION_EVENT_DISTANCE_PX) {
                    val currentAngle = calculateAngle(last, current)
                    if (lastAngle == null) {
                        // we can't start calculating angle changes before having calculated first
                        // angle which serves as a reference point
                        lastAngle = currentAngle
                    } else {
                        val deltaAngle = currentAngle - lastAngle!!

                        cumulativeAngle += normalizeAngleDelta(deltaAngle)
                        lastAngle = currentAngle
                        last = current

                        val fullCircleCompleted = cumulativeAngle >= 2 * Math.PI
                        if (fullCircleCompleted) {
                            cumulativeAngle = 0f
                            circleCount += 1
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                // without checking if gesture is circular we can have gesture doing arches back and
                // forth that finally reaches full circle angle
                if (circleCount >= CIRCLES_COUNT_THRESHOLD && wasGestureCircular(points)) {
                    callback()
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                reset()
            }
        }
    }

    private fun reset() {
        cumulativeAngle = 0f
        lastAngle = null
        circleCount = 0
        points.clear()
    }

    private fun normalizeAngleDelta(deltaAngle: Float): Float {
        // Normalize the deltaAngle to [-PI, PI] range
        val normalizedDelta =
            if (deltaAngle > Math.PI) {
                deltaAngle - (2 * Math.PI).toFloat()
            } else if (deltaAngle < -Math.PI) {
                deltaAngle + (2 * Math.PI).toFloat()
            } else {
                deltaAngle
            }
        return normalizedDelta
    }

    private fun wasGestureCircular(points: List<Point>): Boolean {
        val center =
            Point(
                x = points.map { it.x }.average().toFloat(),
                y = points.map { it.y }.average().toFloat(),
            )
        val radius = points.map { distanceBetween(it, center) }.average().toFloat()
        for (point in points) {
            val distance = distanceBetween(point, center)
            if (abs(distance - radius) > RADIUS_DEVIATION_TOLERANCE * radius) {
                return false
            }
        }
        return true
    }

    private fun distanceBetween(point: Point, center: Point) =
        sqrt((point.x - center.x).toDouble().pow(2.0) + (point.y - center.y).toDouble().pow(2.0))

    private fun calculateAngle(point1: Point, point2: Point): Float {
        return atan2(point2.y - point1.y, point2.x - point1.x)
    }

    companion object {
        /**
         * How much we allow any one point to deviate from average radius. In other words it's a
         * modifier of how difficult is to trigger the gesture. The smaller value the harder it is
         * to trigger. 0.6f seems quite high but:
         * 1. this is just extra check after circles were verified with movement angle
         * 2. it's because of how touchpad events work - they're approximating movement, so doing
         *    smooth circle is ~impossible. Rounded corners square is probably the best thing that
         *    user can do
         */
        private const val RADIUS_DEVIATION_TOLERANCE: Float = 0.7f
        private const val CIRCLES_COUNT_THRESHOLD = 3

        /**
         * Min distance required between motion events to have angular difference calculated. This
         * value is a tradeoff between: minimizing the noise and delaying circle recognition (high
         * value) versus performing calculations very/too often (low value).
         */
        private const val MIN_MOTION_EVENT_DISTANCE_PX = 10
    }
}
