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
import com.android.systemui.biometrics.UdfpsOverlayParams
import com.android.systemui.biometrics.udfps.TouchProcessorResult.Failure
import com.android.systemui.biometrics.udfps.TouchProcessorResult.ProcessedTouch
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

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
            // TODO(b/253085297): Add multitouch support. pointerIndex can be > 0 for ACTION_MOVE.
            val pointerIndex = 0
            val touchData = event.normalize(pointerIndex, overlayParams)
            val isGoodOverlap =
                overlapDetector.isGoodOverlap(touchData, overlayParams.nativeSensorBounds)
            return PreprocessedTouch(touchData, previousPointerOnSensorId, isGoodOverlap)
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> processActionDown(preprocess())
            MotionEvent.ACTION_MOVE -> processActionMove(preprocess())
            MotionEvent.ACTION_UP -> processActionUp(preprocess())
            MotionEvent.ACTION_CANCEL ->
                processActionCancel(event.normalize(pointerIndex = 0, overlayParams))
            else ->
                Failure("Unsupported MotionEvent." + MotionEvent.actionToString(event.actionMasked))
        }
    }
}

private data class PreprocessedTouch(
    val data: NormalizedTouchData,
    val previousPointerOnSensorId: Int,
    val isGoodOverlap: Boolean,
)

private fun processActionDown(touch: PreprocessedTouch): TouchProcessorResult {
    return if (touch.isGoodOverlap) {
        ProcessedTouch(InteractionEvent.DOWN, pointerOnSensorId = touch.data.pointerId, touch.data)
    } else {
        val event =
            if (touch.data.pointerId == touch.previousPointerOnSensorId) {
                InteractionEvent.UP
            } else {
                InteractionEvent.UNCHANGED
            }
        ProcessedTouch(event, pointerOnSensorId = INVALID_POINTER_ID, touch.data)
    }
}

private fun processActionMove(touch: PreprocessedTouch): TouchProcessorResult {
    val hadPointerOnSensor = touch.previousPointerOnSensorId != INVALID_POINTER_ID
    val interactionEvent =
        when {
            touch.isGoodOverlap && !hadPointerOnSensor -> InteractionEvent.DOWN
            !touch.isGoodOverlap && hadPointerOnSensor -> InteractionEvent.UP
            else -> InteractionEvent.UNCHANGED
        }
    val pointerOnSensorId =
        when (interactionEvent) {
            InteractionEvent.UNCHANGED -> touch.previousPointerOnSensorId
            InteractionEvent.DOWN -> touch.data.pointerId
            else -> INVALID_POINTER_ID
        }
    return ProcessedTouch(interactionEvent, pointerOnSensorId, touch.data)
}

private fun processActionUp(touch: PreprocessedTouch): TouchProcessorResult {
    return if (touch.isGoodOverlap) {
        ProcessedTouch(InteractionEvent.UP, pointerOnSensorId = INVALID_POINTER_ID, touch.data)
    } else {
        val event =
            if (touch.previousPointerOnSensorId != INVALID_POINTER_ID) {
                InteractionEvent.UP
            } else {
                InteractionEvent.UNCHANGED
            }
        ProcessedTouch(event, pointerOnSensorId = INVALID_POINTER_ID, touch.data)
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
    return NormalizedTouchData(
        pointerId = getPointerId(pointerIndex),
        x = nativeX,
        y = nativeY,
        minor = nativeMinor,
        major = nativeMajor,
        // TODO(b/259311354): touch orientation should be reported relative to Surface.ROTATION_O.
        orientation = getOrientation(pointerIndex),
        time = eventTime,
        gestureStart = downTime,
    )
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
    if (rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270) {
        RotationUtils.rotatePointF(
            touchPoint,
            RotationUtils.deltaRotation(rot, Surface.ROTATION_0),
            overlayParams.logicalDisplayWidth.toFloat(),
            overlayParams.logicalDisplayHeight.toFloat()
        )
    }
    return touchPoint
}
