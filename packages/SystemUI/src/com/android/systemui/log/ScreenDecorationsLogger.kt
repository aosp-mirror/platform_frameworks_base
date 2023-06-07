/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.log

import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.toRectF
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogLevel.DEBUG
import com.android.systemui.log.LogLevel.ERROR
import com.android.systemui.log.LogLevel.INFO
import com.android.systemui.log.dagger.ScreenDecorationsLog
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "ScreenDecorationsLog"

/**
 * Helper class for logging for [com.android.systemui.ScreenDecorations]
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/ScreenDecorationsLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class ScreenDecorationsLogger
@Inject
constructor(
    @ScreenDecorationsLog private val logBuffer: LogBuffer,
) {
    fun cameraProtectionBoundsForScanningOverlay(bounds: Rect) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = bounds.toShortString() },
            { "Face scanning overlay present camera protection bounds: $str1" }
        )
    }

    fun hwcLayerCameraProtectionBounds(bounds: Rect) {
        logBuffer.log(
            TAG,
            DEBUG,
            { str1 = bounds.toShortString() },
            { "Hwc layer present camera protection bounds: $str1" }
        )
    }

    fun dcvCameraBounds(id: Int, bounds: Rect) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = bounds.toShortString()
                int1 = id
            },
            { "DisplayCutoutView id=$int1 present, camera protection bounds: $str1" }
        )
    }

    fun cutoutViewNotInitialized() {
        logBuffer.log(TAG, ERROR, "CutoutView not initialized showCameraProtection")
    }

    fun boundingRect(boundingRectangle: RectF, context: String) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                str1 = context
                str2 = boundingRectangle.toShortString()
            },
            { "Bounding rect $str1 : $str2" }
        )
    }

    fun boundingRect(boundingRectangle: Rect, context: String) {
        boundingRect(boundingRectangle.toRectF(), context)
    }

    fun onMeasureDimensions(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
        measuredWidth: Int,
        measuredHeight: Int
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                long1 = widthMeasureSpec.toLong()
                long2 = heightMeasureSpec.toLong()
                int1 = measuredWidth
                int2 = measuredHeight
            },
            {
                "Face scanning animation: widthMeasureSpec: $long1 measuredWidth: $int1, " +
                    "heightMeasureSpec: $long2 measuredHeight: $int2"
            }
        )
    }

    fun faceSensorLocation(faceSensorLocation: Point?) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                int1 = faceSensorLocation?.y?.times(2) ?: 0
                str1 = "$faceSensorLocation"
            },
            { "Reinflating view: Face sensor location: $str1, faceScanningHeight: $int1" }
        )
    }

    fun onSensorLocationChanged() {
        logBuffer.log(TAG, DEBUG, "AuthControllerCallback in ScreenDecorations triggered")
    }

    fun cameraProtectionShownOrHidden(
        faceDetectionRunning: Boolean,
        biometricPromptShown: Boolean,
        requestedState: Boolean,
        currentlyShowing: Boolean
    ) {
        logBuffer.log(
            TAG,
            DEBUG,
            {
                bool1 = faceDetectionRunning
                bool2 = biometricPromptShown
                bool3 = requestedState
                bool4 = currentlyShowing
            },
            {
                "isFaceDetectionRunning: $bool1, " +
                    "isBiometricPromptShowing: $bool2, " +
                    "requestedState: $bool3, " +
                    "currentState: $bool4"
            }
        )
    }

    fun biometricEvent(@CompileTimeConstant info: String) {
        logBuffer.log(TAG, DEBUG, info)
    }

    fun cameraProtectionEvent(@CompileTimeConstant cameraProtectionEvent: String) {
        logBuffer.log(TAG, DEBUG, cameraProtectionEvent)
    }

    fun logRotationChangeDeferred(currentRot: Int, newRot: Int) {
        logBuffer.log(
            TAG,
            INFO,
            {
                int1 = currentRot
                int2 = newRot
            },
            { "Rotation changed, deferring $int2, staying at $int2" },
        )
    }

    fun logRotationChanged(oldRot: Int, newRot: Int) {
        logBuffer.log(
            TAG,
            INFO,
            {
                int1 = oldRot
                int2 = newRot
            },
            { "Rotation changed from $int1 to $int2" }
        )
    }

    fun logDisplayModeChanged(currentMode: Int, newMode: Int) {
        logBuffer.log(
            TAG,
            INFO,
            {
                int1 = currentMode
                int2 = newMode
            },
            { "Resolution changed, deferring mode change to $int2, staying at $int1" },
        )
    }

    fun logUserSwitched(newUser: Int) {
        logBuffer.log(
            TAG,
            DEBUG,
            { int1 = newUser },
            { "UserSwitched newUserId=$int1. Updating color inversion setting" },
        )
    }
}
