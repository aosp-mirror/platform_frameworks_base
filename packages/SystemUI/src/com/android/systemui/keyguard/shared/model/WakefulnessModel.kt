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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.model

import com.android.systemui.keyguard.WakefulnessLifecycle

/** Model device wakefulness states. */
data class WakefulnessModel(
    val state: WakefulnessState,
    val lastWakeReason: WakeSleepReason,
    val lastSleepReason: WakeSleepReason,
) {
    fun isStartingToWake() = state == WakefulnessState.STARTING_TO_WAKE

    fun isStartingToSleep() = state == WakefulnessState.STARTING_TO_SLEEP

    fun isStartingToSleepOrAsleep() = isStartingToSleep() || state == WakefulnessState.ASLEEP

    fun isStartingToWakeOrAwake() = isStartingToWake() || state == WakefulnessState.AWAKE

    fun isStartingToSleepFromPowerButton() =
        isStartingToSleep() && lastWakeReason == WakeSleepReason.POWER_BUTTON

    fun isWakingFromPowerButton() =
        isStartingToWake() && lastWakeReason == WakeSleepReason.POWER_BUTTON

    fun isTransitioningFromPowerButton() =
        isStartingToSleepFromPowerButton() || isWakingFromPowerButton()

    fun isAwakeFromTap() =
        state == WakefulnessState.STARTING_TO_WAKE && lastWakeReason == WakeSleepReason.TAP

    companion object {
        fun fromWakefulnessLifecycle(wakefulnessLifecycle: WakefulnessLifecycle): WakefulnessModel {
            return WakefulnessModel(
                WakefulnessState.fromWakefulnessLifecycleInt(wakefulnessLifecycle.wakefulness),
                WakeSleepReason.fromPowerManagerWakeReason(wakefulnessLifecycle.lastWakeReason),
                WakeSleepReason.fromPowerManagerSleepReason(wakefulnessLifecycle.lastSleepReason),
            )
        }
    }
}
