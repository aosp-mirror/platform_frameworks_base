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
 *
 */

package com.android.systemui.keyguard.shared.model

import android.util.Log
import com.android.keyguard.KeyguardClockSwitch

enum class ClockSize(
    @KeyguardClockSwitch.ClockSize val legacyValue: Int,
) {
    SMALL(KeyguardClockSwitch.SMALL),
    LARGE(KeyguardClockSwitch.LARGE);

    companion object {
        private val TAG = ClockSize::class.simpleName!!
        fun fromLegacy(@KeyguardClockSwitch.ClockSize value: Int): ClockSize {
            for (enumVal in enumValues<ClockSize>()) {
                if (enumVal.legacyValue == value) {
                    return enumVal
                }
            }

            Log.e(TAG, "Unrecognized legacy clock size value: $value")
            return LARGE
        }
    }
}

enum class ClockSizeSetting(
    val settingValue: Int,
) {
    DYNAMIC(1),
    SMALL(0);

    companion object {
        private val TAG = ClockSizeSetting::class.simpleName!!
        fun fromSettingValue(value: Int): ClockSizeSetting {
            for (enumVal in enumValues<ClockSizeSetting>()) {
                if (enumVal.settingValue == value) {
                    return enumVal
                }
            }

            Log.e(TAG, "Unrecognized clock setting value: $value")
            return DYNAMIC
        }
    }
}
