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
import android.util.Log
import com.android.systemui.biometrics.EllipseOverlapDetectorParams
import com.android.systemui.dagger.SysUISingleton
import kotlin.math.cos
import kotlin.math.sin

private enum class SensorPixelPosition {
    OUTSIDE, // Pixel that falls outside of sensor circle
    SENSOR, // Pixel within sensor circle
    TARGET // Pixel within sensor center target
}

private val isDebug = true
private val TAG = "EllipseOverlapDetector"

/**
 * Approximates the touch as an ellipse and determines whether the ellipse has a sufficient overlap
 * with the sensor.
 */
@SysUISingleton
class EllipseOverlapDetector(private val params: EllipseOverlapDetectorParams) : OverlapDetector {
    override fun isGoodOverlap(touchData: NormalizedTouchData, nativeSensorBounds: Rect): Boolean {
        var isTargetTouched = false
        var sensorPixels = 0
        var coveredPixels = 0
        for (y in nativeSensorBounds.top..nativeSensorBounds.bottom step params.stepSize) {
            for (x in nativeSensorBounds.left..nativeSensorBounds.right step params.stepSize) {
                // Check where pixel is within the sensor TODO: (b/265836919) This could be improved
                // by precomputing these points
                val pixelPosition =
                    isPartOfSensorArea(
                        x,
                        y,
                        nativeSensorBounds.centerX(),
                        nativeSensorBounds.centerY(),
                        nativeSensorBounds.width() / 2
                    )
                if (pixelPosition != SensorPixelPosition.OUTSIDE) {
                    sensorPixels++

                    // Check if this pixel falls within ellipse touch
                    if (checkPoint(Point(x, y), touchData)) {
                        coveredPixels++

                        // Check that at least one covered pixel is within sensor target
                        isTargetTouched =
                            isTargetTouched or (pixelPosition == SensorPixelPosition.TARGET)
                    }
                }
            }
        }

        val percentage: Float = coveredPixels.toFloat() / sensorPixels
        if (isDebug) {
            Log.v(
                TAG,
                "covered: $coveredPixels, sensor: $sensorPixels, " +
                    "percentage: $percentage, isCenterTouched: $isTargetTouched"
            )
        }

        return percentage >= params.minOverlap && isTargetTouched
    }

    /** Checks if point is in the sensor center target circle, outer circle, or outside of sensor */
    private fun isPartOfSensorArea(x: Int, y: Int, cX: Int, cY: Int, r: Int): SensorPixelPosition {
        val dx = cX - x
        val dy = cY - y

        val disSquared = dx * dx + dy * dy

        return if (disSquared <= (r * params.targetSize) * (r * params.targetSize)) {
            SensorPixelPosition.TARGET
        } else if (disSquared <= r * r) {
            SensorPixelPosition.SENSOR
        } else {
            SensorPixelPosition.OUTSIDE
        }
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
            (a + b) * (a + b) / ((touchData.minor / 2) * (touchData.minor / 2)) +
                (c - d) * (c - d) / ((touchData.major / 2) * (touchData.major / 2))

        return result <= 1
    }
}
