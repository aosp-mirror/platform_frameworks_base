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

/** List of all possible states to transition to/from */
enum class KeyguardState {
    /*
     * The display is completely off, as well as any sensors that would trigger the device to wake
     * up.
     */
    OFF,
    /**
     * The device has entered a special low-power mode within SystemUI. Doze is technically a
     * special dream service implementation. No UI is visible. In this state, a least some
     * low-powered sensors such as lift to wake or tap to wake are enabled, or wake screen for
     * notifications is enabled, allowing the device to quickly wake up.
     */
    DOZING,
    /*
     * A device state after the device times out, which can be from both LOCKSCREEN or GONE states.
     * DOZING is an example of special version of this state. Dreams may be implemented by third
     * parties to present their own UI over keyguard, like a screensaver.
     */
    DREAMING,
    /*
     * A device state after the device times out, which can be from both LOCKSCREEN or GONE states.
     * It is a special version of DREAMING state but not DOZING. The active dream will be windowless
     * and hosted in the lockscreen.
     */
    DREAMING_LOCKSCREEN_HOSTED,
    /**
     * The device has entered a special low-power mode within SystemUI, also called the Always-on
     * Display (AOD). A minimal UI is presented to show critical information. If the device is in
     * low-power mode without a UI, then it is DOZING.
     */
    AOD,
    /*
     * The security screen prompt containing UI to prompt the user to use a biometric credential
     * (ie: fingerprint). When supported, this may show before showing the primary bouncer.
     */
    ALTERNATE_BOUNCER,
    /*
     * The security screen prompt UI, containing PIN, Password, Pattern for the user to verify their
     * credentials.
     */
    PRIMARY_BOUNCER,
    /*
     * Device is actively displaying keyguard UI and is not in low-power mode. Device may be
     * unlocked if SWIPE security method is used, or if face lockscreen bypass is false.
     */
    LOCKSCREEN,
    /*
     * Keyguard is no longer visible. In most cases the user has just authenticated and keyguard
     * is being removed, but there are other cases where the user is swiping away keyguard, such as
     * with SWIPE security method or face unlock without bypass.
     */
    GONE,
    /*
     * An activity is displaying over the keyguard.
     */
    OCCLUDED;

    companion object {

        /** Whether the lockscreen is visible when we're FINISHED in the given state. */
        fun lockscreenVisibleInState(state: KeyguardState): Boolean {
            return state != GONE
        }

        /**
         * Whether the device is awake ([PowerInteractor.isAwake]) when we're FINISHED in the given
         * keyguard state.
         */
        fun deviceIsAwakeInState(state: KeyguardState): Boolean {
            return when (state) {
                OFF -> false
                DOZING -> false
                DREAMING -> false
                DREAMING_LOCKSCREEN_HOSTED -> false
                AOD -> false
                ALTERNATE_BOUNCER -> true
                PRIMARY_BOUNCER -> true
                LOCKSCREEN -> true
                GONE -> true
                OCCLUDED -> true
            }
        }

        /**
         * Whether the device is awake ([PowerInteractor.isAsleep]) when we're FINISHED in the given
         * keyguard state.
         */
        fun deviceIsAsleepInState(state: KeyguardState): Boolean {
            return !deviceIsAwakeInState(state)
        }
    }
}