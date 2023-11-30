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
import com.android.systemui.keyguard.domain.interactor.FromAodTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** Breaks down AOD->GONE transition into discrete steps for corresponding views to consume. */
@ExperimentalCoroutinesApi
@SysUISingleton
class AodToGoneTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
) : DeviceEntryIconTransition {

    private val transitionAnimation =
        KeyguardTransitionAnimationFlow(
            transitionDuration = FromAodTransitionInteractor.TO_GONE_DURATION,
            transitionFlow = interactor.transition(KeyguardState.AOD, KeyguardState.GONE),
        )

    override val deviceEntryParentViewAlpha = transitionAnimation.immediatelyTransitionTo(0f)
}
