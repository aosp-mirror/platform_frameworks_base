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

package com.android.systemui.power.shared.model

import android.os.PowerManager

/** The reason we're waking up or going to sleep, such as pressing the power button. */
enum class WakeSleepReason(
    val isTouch: Boolean,
    @PowerManager.WakeReason val powerManagerWakeReason: Int,
) {
    /** The physical power button was pressed to wake up or sleep the device. */
    POWER_BUTTON(isTouch = false, PowerManager.WAKE_REASON_POWER_BUTTON),

    /** The user has tapped or double tapped to wake the screen. */
    TAP(isTouch = true, PowerManager.WAKE_REASON_TAP),

    /** The user performed some sort of gesture to wake the screen. */
    GESTURE(isTouch = true, PowerManager.WAKE_REASON_GESTURE),

    /** Waking up because a wake key other than power was pressed. */
    KEY(isTouch = false, PowerManager.WAKE_REASON_WAKE_KEY),

    /** Waking up because a wake motion was performed */
    MOTION(isTouch = false, PowerManager.WAKE_REASON_WAKE_MOTION),

    /** Waking due to the lid being opened. */
    LID(isTouch = false, PowerManager.WAKE_REASON_LID),

    /** Waking the device due to unfolding of a foldable device. */
    UNFOLD(isTouch = false, PowerManager.WAKE_REASON_UNFOLD_DEVICE),

    /** Waking up due to a user performed lift gesture. */
    LIFT(isTouch = false, PowerManager.WAKE_REASON_LIFT),

    /** Waking up due to a user interacting with a biometric. */
    BIOMETRIC(isTouch = false, PowerManager.WAKE_REASON_BIOMETRIC),

    /** Something else happened to wake up or sleep the device. */
    OTHER(isTouch = false, PowerManager.WAKE_REASON_UNKNOWN);

    companion object {
        fun fromPowerManagerWakeReason(reason: Int): WakeSleepReason {
            return when (reason) {
                PowerManager.WAKE_REASON_POWER_BUTTON -> POWER_BUTTON
                PowerManager.WAKE_REASON_TAP -> TAP
                PowerManager.WAKE_REASON_GESTURE -> GESTURE
                PowerManager.WAKE_REASON_WAKE_KEY -> KEY
                PowerManager.WAKE_REASON_WAKE_MOTION -> MOTION
                PowerManager.WAKE_REASON_LID -> LID
                PowerManager.WAKE_REASON_UNFOLD_DEVICE -> UNFOLD
                PowerManager.WAKE_REASON_LIFT -> LIFT
                PowerManager.WAKE_REASON_BIOMETRIC -> BIOMETRIC
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
