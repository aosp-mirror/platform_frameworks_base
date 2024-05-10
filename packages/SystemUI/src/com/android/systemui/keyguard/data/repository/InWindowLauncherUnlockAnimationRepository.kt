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

package com.android.systemui.keyguard.data.repository

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.InWindowLauncherUnlockAnimationInteractor
import com.android.systemui.shared.system.smartspace.ILauncherUnlockAnimationController
import com.android.systemui.shared.system.smartspace.SmartspaceState
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * State related to System UI's handling of the in-window Launcher unlock animations. This includes
 * the staggered icon entry animation that plays during unlock, as well as the smartspace shared
 * element animation, if supported.
 *
 * While the animations themselves occur fully in the Launcher window, System UI is responsible for
 * preparing/starting the animations, as well as synchronizing the smartspace state so that the two
 * smartspaces appear visually identical for the shared element animation.
 */
@SysUISingleton
class InWindowLauncherUnlockAnimationRepository @Inject constructor() {

    /**
     * Whether we have called [ILauncherUnlockAnimationController.playUnlockAnimation] during this
     * unlock sequence. This value is set back to false once
     * [InWindowLauncherUnlockAnimationInteractor.shouldStartInWindowAnimation] reverts to false,
     * which happens when we're no longer in transition to GONE or if the remote animation ends or
     * is cancelled.
     */
    val startedUnlockAnimation = MutableStateFlow(false)

    /**
     * The unlock amount we've explicitly passed to
     * [ILauncherUnlockAnimationController.setUnlockAmount]. This is used whenever System UI is
     * directly controlling the amount of the unlock animation, such as during a manual swipe to
     * unlock gesture.
     *
     * This value is *not* updated if we called
     * [ILauncherUnlockAnimationController.playUnlockAnimation] to ask Launcher to animate all the
     * way unlocked, since that animator is running in the Launcher window.
     */
    val manualUnlockAmount: MutableStateFlow<Float?> = MutableStateFlow(null)

    /**
     * The class name of the Launcher activity that provided us with a
     * [ILauncherUnlockAnimationController], if applicable. We can use this to check if that
     * launcher is underneath the lockscreen before playing in-window animations.
     *
     * If null, we have not been provided with a launcher unlock animation controller.
     */
    val launcherActivityClass: MutableStateFlow<String?> = MutableStateFlow(null)

    /**
     * Information about the Launcher's smartspace, which is passed to us via
     * [ILauncherUnlockAnimationController].
     */
    val launcherSmartspaceState: MutableStateFlow<SmartspaceState?> = MutableStateFlow(null)

    fun setStartedUnlockAnimation(started: Boolean) {
        startedUnlockAnimation.value = started
    }

    fun setManualUnlockAmount(amount: Float?) {
        manualUnlockAmount.value = amount
    }

    fun setLauncherActivityClass(className: String) {
        launcherActivityClass.value = className
    }

    fun setLauncherSmartspaceState(state: SmartspaceState?) {
        launcherSmartspaceState.value = state
    }
}
