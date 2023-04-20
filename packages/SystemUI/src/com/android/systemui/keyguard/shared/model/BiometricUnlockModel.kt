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

/** Model device wakefulness states. */
enum class BiometricUnlockModel {
    /** Mode in which we don't need to wake up the device when we authenticate. */
    NONE,
    /**
     * Mode in which we wake up the device, and directly dismiss Keyguard. Active when we acquire a
     * fingerprint while the screen is off and the device was sleeping.
     */
    WAKE_AND_UNLOCK,
    /**
     * Mode in which we wake the device up, and fade out the Keyguard contents because they were
     * already visible while pulsing in doze mode.
     */
    WAKE_AND_UNLOCK_PULSING,
    /**
     * Mode in which we wake up the device, but play the normal dismiss animation. Active when we
     * acquire a fingerprint pulsing in doze mode.
     */
    SHOW_BOUNCER,
    /**
     * Mode in which we only wake up the device, and keyguard was not showing when we authenticated.
     */
    ONLY_WAKE,
    /**
     * Mode in which fingerprint unlocks the device or passive auth (ie face auth) unlocks the
     * device while being requested when keyguard is occluded or showing.
     */
    UNLOCK_COLLAPSING,
    /** When bouncer is visible and will be dismissed. */
    DISMISS_BOUNCER,
    /** Mode in which fingerprint wakes and unlocks the device from a dream. */
    WAKE_AND_UNLOCK_FROM_DREAM;

    companion object {
        private val wakeAndUnlockModes =
            setOf(WAKE_AND_UNLOCK, WAKE_AND_UNLOCK_FROM_DREAM, WAKE_AND_UNLOCK_PULSING)

        fun isWakeAndUnlock(model: BiometricUnlockModel): Boolean {
            return wakeAndUnlockModes.contains(model)
        }
    }
}
