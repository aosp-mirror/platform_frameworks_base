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
import com.android.systemui.biometrics.shared.model.DisplayRotation
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
        shouldRotate: Boolean,
        fpDetectRunning: Boolean,
        sensorWidth: Int
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = visible
                int1 = location.x
                int2 = location.y
                bool2 = shouldRotate
                bool3 = fpDetectRunning
                long1 = sensorWidth.toLong()
            },
            {
                "SFPS progress bar state changed: visible: $bool1, " +
                    "sensorLocation (x, y): ($int1, $int2), " +
                    "shouldRotate = $bool2, " +
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

    fun logStateChange(sfpsAvailable: Boolean, settingEnabled: Boolean) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                bool1 = sfpsAvailable
                bool2 = settingEnabled
            },
            { "SFPS rest to unlock state changed: sfpsAvailable: $bool1, settingEnabled: $bool2" }
        )
    }

    fun sensorLocationStateChanged(
        windowSize: Rect?,
        rotation: DisplayRotation,
        displayWidth: Int,
        displayHeight: Int,
        sensorWidth: Int,
        sensorVerticalInDefaultOrientation: Boolean
    ) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = "$windowSize"
                str2 = rotation.name
                int1 = displayWidth
                int2 = displayHeight
                long1 = sensorWidth.toLong()
                bool1 = sensorVerticalInDefaultOrientation
            },
            {
                "sensorLocation state changed: " +
                    "windowSize: $str1, " +
                    "rotation: $str2, " +
                    "widthInRotation0: $int1, " +
                    "heightInRotation0: $int2, " +
                    "sensorWidth: $long1, " +
                    "sensorVerticalInDefaultOrientation: $bool1"
            }
        )
    }
}
