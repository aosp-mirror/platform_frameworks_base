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

package com.android.systemui.keyguard.ui.binder

import com.android.systemui.keyguard.ui.view.InWindowLauncherUnlockAnimationManager
import com.android.systemui.keyguard.ui.viewmodel.InWindowLauncherAnimationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Binds the [InWindowLauncherUnlockAnimationManager] "view", which manages the lifecycle and state
 * of the in-window Launcher animation.
 */
object InWindowLauncherAnimationViewBinder {

    @JvmStatic
    fun bind(
        viewModel: InWindowLauncherAnimationViewModel,
        inWindowLauncherUnlockAnimationManager: InWindowLauncherUnlockAnimationManager,
        scope: CoroutineScope
    ) {
        scope.launch {
            viewModel.shouldPrepareForInWindowAnimation.collect { shouldPrepare ->
                if (shouldPrepare) {
                    inWindowLauncherUnlockAnimationManager.prepareForUnlock()
                } else {
                    // If we no longer meet the conditions to prepare for unlock, we'll need to
                    // manually set Launcher unlocked if we didn't start the unlock animation, or it
                    // will remain "prepared" (blank) forever.
                    inWindowLauncherUnlockAnimationManager.ensureUnlockedOrAnimatingUnlocked()
                }
            }
        }

        scope.launch {
            viewModel.shouldStartInWindowAnimation.collect { shouldStart ->
                if (shouldStart) {
                    inWindowLauncherUnlockAnimationManager.playUnlockAnimation(unlocked = true)
                } else {
                    // Once the conditions to start the animation are no longer met, clear whether
                    // we started the animation, since we'll need to start it again if the
                    // conditions become true again.
                    inWindowLauncherUnlockAnimationManager.clearStartedUnlockAnimation()
                }
            }
        }
    }
}
