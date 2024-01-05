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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.BouncerLog
import javax.inject.Inject

private const val TAG = "SideFpsLogger"

/**
 * Helper class for logging for SFPS related functionality
 *
 * To enable logcat echoing for an entire buffer:
 * ```
 *   adb shell settings put global systemui/buffer/BouncerLog <logLevel>
 *
 * ```
 */
@SysUISingleton
class SideFpsLogger @Inject constructor(@BouncerLog private val buffer: LogBuffer) {
    fun sfpsProgressBarStateChanged(
        visible: Boolean,
        location: Point,
        fpDetectRunning: Boolean,
        sensorWidth: Int,
        rotation: Float,
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = visible
                int1 = location.x
                int2 = location.y
                str1 = "$rotation"
                bool3 = fpDetectRunning
                long1 = sensorWidth.toLong()
            },
            {
                "SFPS progress bar state changed: visible: $bool1, " +
                    "sensorLocation (x, y): ($int1, $int2), " +
                    "rotation = $str1, " +
                    "fpDetectRunning: $bool3, " +
                    "sensorWidth: $long1"
            }
        )
    }

    fun hidingSfpsIndicator() {
        buffer.log(TAG, LogLevel.DEBUG, "hiding SFPS indicator to show progress bar")
    }

    fun showingSfpsIndicator() {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            "Requesting show SFPS indicator because progress bar " +
                "is being hidden and FP detect is currently running"
        )
    }

    fun isProlongedTouchRequiredForAuthenticationChanged(enabled: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { bool1 = enabled },
            { "isProlongedTouchRequiredForAuthentication: $bool1" }
        )
    }

    fun sensorLocationStateChanged(
        pointOnScreenX: Int,
        pointOnScreenY: Int,
        sensorLength: Int,
        isSensorVerticalInDefaultOrientation: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                int1 = pointOnScreenX
                int2 = pointOnScreenY
                str2 = "$sensorLength"
                bool1 = isSensorVerticalInDefaultOrientation
            },
            {
                "SideFpsSensorLocation state changed: " +
                    "pointOnScreen: ($int1, $int2), " +
                    "sensorLength: $str2, " +
                    "sensorVerticalInDefaultOrientation: $bool1"
            }
        )
    }

    fun authDurationChanged(duration: Long) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            { long1 = duration },
            { "SideFpsSensor auth duration changed: $long1" }
        )
    }
}
