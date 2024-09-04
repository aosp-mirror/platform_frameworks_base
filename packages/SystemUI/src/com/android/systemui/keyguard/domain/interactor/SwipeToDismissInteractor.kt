/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.Utils.Companion.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Handles logic around the swipe to dismiss gesture, where the user swipes up on the dismissable
 * lockscreen to unlock the device.
 */
@SysUISingleton
class SwipeToDismissInteractor
@Inject
constructor(
    @Background backgroundScope: CoroutineScope,
    shadeRepository: ShadeRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    keyguardInteractor: KeyguardInteractor,
) {
    /**
     * Emits a [FlingInfo] whenever a swipe to dismiss gesture has started a fling animation on the
     * lockscreen while it's dismissable.
     *
     * This value is collected by [FromLockscreenTransitionInteractor] to start a transition from
     * LOCKSCREEN -> GONE, and by [KeyguardSurfaceBehindInteractor] to match the surface remote
     * animation's velocity to the fling velocity, if applicable.
     */
    val dismissFling =
        shadeRepository.currentFling
            .sample(
                transitionInteractor.startedKeyguardTransitionStep,
                keyguardInteractor.isKeyguardDismissible,
                keyguardInteractor.statusBarState,
            )
            .filter { (flingInfo, startedStep, keyguardDismissable, statusBarState) ->
                flingInfo != null &&
                    !flingInfo.expand &&
                    statusBarState != StatusBarState.SHADE_LOCKED &&
                    startedStep.to == KeyguardState.LOCKSCREEN &&
                    keyguardDismissable
            }
            .map { (flingInfo, _) -> flingInfo }
            .stateIn(backgroundScope, SharingStarted.Eagerly, null)
}
