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

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.InWindowLauncherUnlockAnimationInteractor
import javax.inject.Inject

@SysUISingleton
class InWindowLauncherAnimationViewModel
@Inject
constructor(interactor: InWindowLauncherUnlockAnimationInteractor) {

    /**
     * Whether we should call [ILauncherUnlockAnimationController.prepareForUnlock] to set up the
     * Launcher icons for the in-window unlock.
     *
     * We'll do this as soon as we're transitioning to GONE when the necessary preconditions are
     * met.
     */
    val shouldPrepareForInWindowAnimation = interactor.transitioningToGoneWithInWindowAnimation

    /**
     * Whether we should call [ILauncherUnlockAnimationController.playUnlockAnimation] to start the
     * in-window unlock animation.
     */
    val shouldStartInWindowAnimation = interactor.shouldStartInWindowAnimation
}
