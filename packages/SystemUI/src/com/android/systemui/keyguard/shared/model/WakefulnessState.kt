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

import com.android.systemui.keyguard.WakefulnessLifecycle

enum class WakefulnessState {
    /** The device is asleep and not interactive. */
    ASLEEP,
    /** Received a signal that the device is beginning to wake up. */
    STARTING_TO_WAKE,
    /** Device is now fully awake and interactive. */
    AWAKE,
    /** Signal that the device is now going to sleep. */
    STARTING_TO_SLEEP;

    companion object {
        fun fromWakefulnessLifecycleInt(
            @WakefulnessLifecycle.Wakefulness value: Int
        ): WakefulnessState {
            return when (value) {
                WakefulnessLifecycle.WAKEFULNESS_ASLEEP -> ASLEEP
                WakefulnessLifecycle.WAKEFULNESS_WAKING -> STARTING_TO_WAKE
                WakefulnessLifecycle.WAKEFULNESS_AWAKE -> AWAKE
                WakefulnessLifecycle.WAKEFULNESS_GOING_TO_SLEEP -> STARTING_TO_SLEEP
                else -> throw IllegalArgumentException("Invalid Wakefulness value: $value")
            }
        }
    }
}
