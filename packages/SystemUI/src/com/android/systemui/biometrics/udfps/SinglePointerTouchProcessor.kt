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

import android.graphics.PointF
import android.util.RotationUtils
import android.view.MotionEvent
import android.view.MotionEvent.INVALID_POINTER_ID
import android.view.Surface
import com.android.systemui.biometrics.shared.model.UdfpsOverlayParams
import com.android.systemui.biometrics.udfps.TouchProcessorResult.Failure
import com.android.systemui.biometrics.udfps.TouchProcessorResult.ProcessedTouch
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

private val SUPPORTED_ROTATIONS = setOf(Surface.ROTATION_90, Surface.ROTATION_270)

/**
 * TODO(b/259140693): Consider using an object pool of TouchProcessorResult to avoid allocations.
 */
@SysUISingleton
class SinglePointerTouchProcessor @Inject constructor(val overlapDetector: OverlapDetector) :
    TouchProcessor {

    override fun processTouch(
        event: MotionEvent,
        previousPointerOnSensorId: Int,
        overlayParams: UdfpsOverlayParams,
    ): TouchProcessorResult {
        fun preprocess(): PreprocessedTouch {
            val touchData = List(event.pointerCount) { event.normalize(it, overlayParams) }
            val pointersOnSensor =
                touchData
                    .filter {
                        overlapDetector.isGoodOverlap(
                            it,
                            overlayParams.nativeSensorBounds,
                            overlayParams.nativeOverlayBounds
                        )
                    }
                    .map { it.pointerId }
            return PreprocessedTouch(touchData, previousPointerOnSensorId, pointersOnSensor)
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_ENTER,
            MotionEvent.ACTION_HOVER_MOVE -> processActionMove(preprocess())
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_HOVER_EXIT ->
                processActionUp(preprocess(), event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> processActionCancel(NormalizedTouchData())
            else ->
                Failure("Unsupported MotionEvent." + MotionEvent.actionToString(event.actionMasked))
        }
    }
}

/**
 * [data] contains a list of NormalizedTouchData for pointers in the motionEvent ordered by
 * pointerIndex
 *
 * [previousPointerOnSensorId] the pointerId of the previous pointer on the sensor,
 * [MotionEvent.INVALID_POINTER_ID] if none
 *
 * [pointersOnSensor] contains a list of ids of pointers on the sensor
 */
private data class PreprocessedTouch(
    val data: List<NormalizedTouchData>,
    val previousPointerOnSensorId: Int,
    val pointersOnSensor: List<Int>,
)

private fun processActionMove(touch: PreprocessedTouch): TouchProcessorResult {
    val hadPointerOnSensor = touch.previousPointerOnSensorId != INVALID_POINTER_ID
    val hasPointerOnSensor = touch.pointersOnSensor.isNotEmpty()
    val pointerOnSensorId = touch.pointersOnSensor.firstOrNull() ?: INVALID_POINTER_ID

    return if (!hadPointerOnSensor && hasPointerOnSensor) {
        val data = touch.data.find { it.pointerId == pointerOnSensorId } ?: NormalizedTouchData()
        ProcessedTouch(InteractionEvent.DOWN, data.pointerId, data)
    } else if (hadPointerOnSensor && !hasPointerOnSensor) {
        val data =
            touch.data.find { it.pointerId == touch.previousPointerOnSensorId }
                ?: NormalizedTouchData()
        ProcessedTouch(InteractionEvent.UP, INVALID_POINTER_ID, data)
    } else {
        val data =
            touch.data.find { it.pointerId == pointerOnSensorId }
                ?: touch.data.firstOrNull() ?: NormalizedTouchData()
        ProcessedTouch(InteractionEvent.UNCHANGED, pointerOnSensorId, data)
    }
}

private fun processActionUp(touch: PreprocessedTouch, actionId: Int): TouchProcessorResult {
    // Finger lifted and it was the only finger on the sensor
    return if (touch.pointersOnSensor.size == 1 && touch.pointersOnSensor.contains(actionId)) {
        val data = touch.data.find { it.pointerId == actionId } ?: NormalizedTouchData()
        ProcessedTouch(InteractionEvent.UP, pointerOnSensorId = INVALID_POINTER_ID, data)
    } else {
        // Pick new pointerOnSensor that's not the finger that was lifted
        val pointerOnSensorId = touch.pointersOnSensor.find { it != actionId } ?: INVALID_POINTER_ID
        val data =
            touch.data.find { it.pointerId == pointerOnSensorId }
                ?: touch.data.firstOrNull() ?: NormalizedTouchData()
        ProcessedTouch(InteractionEvent.UNCHANGED, pointerOnSensorId, data)
    }
}

private fun processActionCancel(data: NormalizedTouchData): TouchProcessorResult {
    return ProcessedTouch(InteractionEvent.CANCEL, pointerOnSensorId = INVALID_POINTER_ID, data)
}

/**
 * Returns the touch information from the given [MotionEvent] with the relevant fields mapped to
 * natural orientation and native resolution.
 */
private fun MotionEvent.normalize(
    pointerIndex: Int,
    overlayParams: UdfpsOverlayParams
): NormalizedTouchData {
    val naturalTouch: PointF = rotateToNaturalOrientation(pointerIndex, overlayParams)
    val nativeX = naturalTouch.x / overlayParams.scaleFactor
    val nativeY = naturalTouch.y / overlayParams.scaleFactor
    val nativeMinor: Float = getTouchMinor(pointerIndex) / overlayParams.scaleFactor
    val nativeMajor: Float = getTouchMajor(pointerIndex) / overlayParams.scaleFactor
    var nativeOrientation: Float = getOrientation(pointerIndex)
    if (SUPPORTED_ROTATIONS.contains(overlayParams.rotation)) {
        nativeOrientation = toRadVerticalFromRotated(nativeOrientation.toDouble()).toFloat()
    }
    return NormalizedTouchData(
        pointerId = getPointerId(pointerIndex),
        x = nativeX,
        y = nativeY,
        minor = nativeMinor,
        major = nativeMajor,
        orientation = nativeOrientation,
        time = eventTime,
        gestureStart = downTime,
    )
}

private fun toRadVerticalFromRotated(rad: Double): Double {
    val piBound = ((rad % Math.PI) + Math.PI / 2) % Math.PI
    return if (piBound < Math.PI / 2.0) piBound else piBound - Math.PI
}

/**
 * Returns the [MotionEvent.getRawX] and [MotionEvent.getRawY] of the given pointer as if the device
 * is in the [Surface.ROTATION_0] orientation.
 */
private fun MotionEvent.rotateToNaturalOrientation(
    pointerIndex: Int,
    overlayParams: UdfpsOverlayParams
): PointF {
    val touchPoint = PointF(getRawX(pointerIndex), getRawY(pointerIndex))
    val rot = overlayParams.rotation
    if (SUPPORTED_ROTATIONS.contains(rot)) {
        RotationUtils.rotatePointF(
            touchPoint,
            RotationUtils.deltaRotation(rot, Surface.ROTATION_0),
            overlayParams.logicalDisplayWidth.toFloat(),
            overlayParams.logicalDisplayHeight.toFloat()
        )
    }
    return touchPoint
}
