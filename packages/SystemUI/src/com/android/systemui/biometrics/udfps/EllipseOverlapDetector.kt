/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics.udfps

import android.graphics.Point
import android.graphics.Rect
import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Approximates the touch as an ellipse and determines whether the ellipse has a sufficient overlap
 * with the sensor.
 */
@SysUISingleton
class EllipseOverlapDetector(private val neededPoints: Int = 2) : OverlapDetector {

    override fun isGoodOverlap(touchData: NormalizedTouchData, nativeSensorBounds: Rect): Boolean {
        val points = calculateSensorPoints(nativeSensorBounds)
        return points.count { checkPoint(it, touchData) } >= neededPoints
    }

    private fun checkPoint(point: Point, touchData: NormalizedTouchData): Boolean {
        // Calculate if sensor point is within ellipse
        // Formula: ((cos(o)(xE - xS) + sin(o)(yE - yS))^2 / a^2) + ((sin(o)(xE - xS) + cos(o)(yE -
        // yS))^2 / b^2) <= 1
        val a: Float = cos(touchData.orientation) * (point.x - touchData.x)
        val b: Float = sin(touchData.orientation) * (point.y - touchData.y)
        val c: Float = sin(touchData.orientation) * (point.x - touchData.x)
        val d: Float = cos(touchData.orientation) * (point.y - touchData.y)
        val result =
            (a + b).pow(2) / (touchData.minor / 2).pow(2) +
                (c - d).pow(2) / (touchData.major / 2).pow(2)

        return result <= 1
    }

    @VisibleForTesting
    fun calculateSensorPoints(sensorBounds: Rect): List<Point> {
        val sensorX = sensorBounds.centerX()
        val sensorY = sensorBounds.centerY()
        val cornerOffset: Int = sensorBounds.width() / 4
        val sideOffset: Int = sensorBounds.width() / 3

        return listOf(
            Point(sensorX - cornerOffset, sensorY - cornerOffset),
            Point(sensorX, sensorY - sideOffset),
            Point(sensorX + cornerOffset, sensorY - cornerOffset),
            Point(sensorX - sideOffset, sensorY),
            Point(sensorX, sensorY),
            Point(sensorX + sideOffset, sensorY),
            Point(sensorX - cornerOffset, sensorY + cornerOffset),
            Point(sensorX, sensorY + sideOffset),
            Point(sensorX + cornerOffset, sensorY + cornerOffset)
        )
    }
}
