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
import com.android.systemui.keyguard.shared.model.WakeSleepReason.GESTURE
import com.android.systemui.keyguard.shared.model.WakeSleepReason.POWER_BUTTON
import com.android.systemui.keyguard.shared.model.WakeSleepReason.TAP
import com.android.systemui.keyguard.shared.model.WakefulnessState.ASLEEP
import com.android.systemui.keyguard.shared.model.WakefulnessState.AWAKE
import com.android.systemui.keyguard.shared.model.WakefulnessState.STARTING_TO_SLEEP
import com.android.systemui.keyguard.shared.model.WakefulnessState.STARTING_TO_WAKE

/** Model device wakefulness states. */
data class WakefulnessModel(
    val state: WakefulnessState,
    val lastWakeReason: WakeSleepReason,
    val lastSleepReason: WakeSleepReason,
) {
    fun isStartingToWake() = state == STARTING_TO_WAKE

    fun isStartingToSleep() = state == STARTING_TO_SLEEP

    private fun isAsleep() = state == ASLEEP

    private fun isAwake() = state == AWAKE

    fun isStartingToWakeOrAwake() = isStartingToWake() || isAwake()

    fun isStartingToSleepOrAsleep() = isStartingToSleep() || isAsleep()

    fun isDeviceInteractive() = !isAsleep()

    fun isWakingFrom(wakeSleepReason: WakeSleepReason) =
        isStartingToWake() && lastWakeReason == wakeSleepReason

    fun isStartingToSleepFrom(wakeSleepReason: WakeSleepReason) =
        isStartingToSleep() && lastSleepReason == wakeSleepReason

    fun isTransitioningFromPowerButton() =
        isStartingToSleepFrom(POWER_BUTTON) || isWakingFrom(POWER_BUTTON)

    fun isDeviceInteractiveFromTapOrGesture(): Boolean {
        return isDeviceInteractive() && (lastWakeReason == TAP || lastWakeReason == GESTURE)
    }

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
