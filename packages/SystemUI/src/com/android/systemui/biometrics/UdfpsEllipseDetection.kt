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

package com.android.systemui.biometrics

import android.graphics.Point
import android.graphics.Rect
import android.util.RotationUtils
import android.view.MotionEvent
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val TAG = "UdfpsEllipseDetection"

private const val NEEDED_POINTS = 2

class UdfpsEllipseDetection(overlayParams: UdfpsOverlayParams) {
    var sensorRect = Rect()
    var points: Array<Point> = emptyArray()

    init {
        sensorRect = Rect(overlayParams.sensorBounds)

        points = calculateSensorPoints(sensorRect)
    }

    fun updateOverlayParams(params: UdfpsOverlayParams) {
        sensorRect = Rect(params.sensorBounds)

        val rot = params.rotation
        RotationUtils.rotateBounds(
            sensorRect,
            params.naturalDisplayWidth,
            params.naturalDisplayHeight,
            rot
        )

        points = calculateSensorPoints(sensorRect)
    }

    fun isGoodEllipseOverlap(event: MotionEvent): Boolean {
        return points.count { checkPoint(event, it) } >= NEEDED_POINTS
    }

    private fun checkPoint(event: MotionEvent, point: Point): Boolean {
        // Calculate if sensor point is within ellipse
        // Formula: ((cos(o)(xE - xS) + sin(o)(yE - yS))^2 / a^2) + ((sin(o)(xE - xS) + cos(o)(yE -
        // yS))^2 / b^2) <= 1
        val a: Float = cos(event.orientation) * (point.x - event.rawX)
        val b: Float = sin(event.orientation) * (point.y - event.rawY)
        val c: Float = sin(event.orientation) * (point.x - event.rawX)
        val d: Float = cos(event.orientation) * (point.y - event.rawY)
        val result =
            (a + b).pow(2) / (event.touchMinor / 2).pow(2) +
                (c - d).pow(2) / (event.touchMajor / 2).pow(2)

        return result <= 1
    }
}

fun calculateSensorPoints(sensorRect: Rect): Array<Point> {
    val sensorX = sensorRect.centerX()
    val sensorY = sensorRect.centerY()
    val cornerOffset: Int = sensorRect.width() / 4
    val sideOffset: Int = sensorRect.width() / 3

    return arrayOf(
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
