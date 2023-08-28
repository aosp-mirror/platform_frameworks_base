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

package com.android.systemui.keyguard.shared.model

import android.os.PowerManager

/** The reason we're waking up or going to sleep, such as pressing the power button. */
enum class WakeSleepReason {
    /** The physical power button was pressed to wake up or sleep the device. */
    POWER_BUTTON,

    /** The user has taped or double tapped to wake the screen */
    TAP,

    /** Something else happened to wake up or sleep the device. */
    OTHER;

    companion object {
        fun fromPowerManagerWakeReason(reason: Int): WakeSleepReason {
            return when (reason) {
                PowerManager.WAKE_REASON_POWER_BUTTON -> POWER_BUTTON
                PowerManager.WAKE_REASON_TAP -> TAP
                else -> OTHER
            }
        }

        fun fromPowerManagerSleepReason(reason: Int): WakeSleepReason {
            return when (reason) {
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON -> POWER_BUTTON
                else -> OTHER
            }
        }
    }
}
