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

import android.util.Log
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes

/** List of all possible states to transition to/from */
enum class KeyguardState {
    /**
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
    /**
     * A device state after the device times out, which can be from both LOCKSCREEN or GONE states.
     * DOZING is an example of special version of this state. Dreams may be implemented by third
     * parties to present their own UI over keyguard, like a screensaver.
     */
    DREAMING,
    /**
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
    /**
     * The security screen prompt containing UI to prompt the user to use a biometric credential
     * (ie: fingerprint). When supported, this may show before showing the primary bouncer.
     */
    ALTERNATE_BOUNCER,
    /**
     * The security screen prompt UI, containing PIN, Password, Pattern for the user to verify their
     * credentials.
     */
    @Deprecated(
        "This state won't exist anymore when scene container gets enabled. If you are " +
            "writing prod code today, make sure to either use flag aware APIs in " +
            "[KeyguardTransitionInteractor] or flag appropriately with [SceneContainerFlag]."
    )
    PRIMARY_BOUNCER,
    /**
     * Device is actively displaying keyguard UI and is not in low-power mode. Device may be
     * unlocked if SWIPE security method is used, or if face lockscreen bypass is false.
     */
    LOCKSCREEN,
    /**
     * Device is locked or on dream and user has swiped from the right edge to enter the glanceable
     * hub UI. From this state, the user can swipe from the left edge to go back to the lock screen
     * or dream, as well as swipe down for the notifications and up for the bouncer.
     */
    @Deprecated(
        "This state won't exist anymore when scene container gets enabled. If you are " +
            "writing prod code today, make sure to either use flag aware APIs in " +
            "[KeyguardTransitionInteractor] or flag appropriately with [SceneContainerFlag]."
    )
    GLANCEABLE_HUB,
    /**
     * Keyguard is no longer visible. In most cases the user has just authenticated and keyguard is
     * being removed, but there are other cases where the user is swiping away keyguard, such as
     * with SWIPE security method or face unlock without bypass.
     */
    @Deprecated(
        "This state won't exist anymore when scene container gets enabled. If you are " +
            "writing prod code today, make sure to either use flag aware APIs in " +
            "[KeyguardTransitionInteractor] or flag appropriately with [SceneContainerFlag]."
    )
    GONE,
    /**
     * Only used in scene framework. This means we are currently on any scene framework scene that
     * is not Lockscreen. Transitions to and from UNDEFINED are always bound to the
     * [SceneTransitionLayout] scene transition that either transitions to or from the Lockscreen
     * scene. These transitions are automatically handled by [LockscreenSceneTransitionInteractor].
     */
    UNDEFINED,
    /** An activity is displaying over the keyguard. */
    OCCLUDED;

    fun checkValidState() {
        val isStateValid: Boolean
        val isEnabled: String
        if (SceneContainerFlag.isEnabled) {
            isStateValid = this === mapToSceneContainerState()
            isEnabled = "enabled"
        } else {
            isStateValid = this !== UNDEFINED
            isEnabled = "disabled"
        }

        if (!isStateValid) {
            Log.e("KeyguardState", "$this is not a valid state when scene container is $isEnabled")
        }
    }

    fun mapToSceneContainerState(): KeyguardState {
        return when (this) {
            OFF,
            DOZING,
            DREAMING,
            DREAMING_LOCKSCREEN_HOSTED,
            AOD,
            ALTERNATE_BOUNCER,
            OCCLUDED,
            LOCKSCREEN -> this
            GLANCEABLE_HUB,
            PRIMARY_BOUNCER,
            GONE,
            UNDEFINED -> UNDEFINED
        }
    }

    fun mapToSceneContainerScene(): SceneKey? {
        return when (this) {
            OFF,
            DOZING,
            DREAMING,
            DREAMING_LOCKSCREEN_HOSTED,
            AOD,
            ALTERNATE_BOUNCER,
            OCCLUDED,
            LOCKSCREEN -> Scenes.Lockscreen
            GLANCEABLE_HUB -> Scenes.Communal
            PRIMARY_BOUNCER -> Scenes.Bouncer
            GONE -> Scenes.Gone
            UNDEFINED -> null
        }
    }

    companion object {

        /**
         * Whether the device is awake ([PowerInteractor.isAwake]) when we're FINISHED in the given
         * keyguard state.
         */
        fun deviceIsAwakeInState(state: KeyguardState): Boolean {
            state.checkValidState()
            return when (state) {
                OFF -> false
                DOZING -> false
                DREAMING -> false
                DREAMING_LOCKSCREEN_HOSTED -> false
                GLANCEABLE_HUB -> true
                AOD -> false
                ALTERNATE_BOUNCER -> true
                PRIMARY_BOUNCER -> true
                LOCKSCREEN -> true
                GONE -> true
                OCCLUDED -> true
                UNDEFINED -> true
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
