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
import com.android.systemui.log.dagger.ScreenDecorationsLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel.DEBUG
import com.android.systemui.plugins.log.LogLevel.ERROR
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
}
