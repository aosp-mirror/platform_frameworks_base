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
import com.android.systemui.keyguard.domain.interactor.FromDozingTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow

/**
 * Breaks down DOZING->LOCKSCREEN transition into discrete steps for corresponding views to consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class DozingToLockscreenTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
) : DeviceEntryIconTransition {
    private val transitionAnimation: KeyguardTransitionAnimationFlow =
        KeyguardTransitionAnimationFlow(
            transitionDuration = FromDozingTransitionInteractor.TO_LOCKSCREEN_DURATION,
            transitionFlow = interactor.dozingToLockscreenTransition,
        )

    override val deviceEntryParentViewAlpha: Flow<Float> =
        transitionAnimation.immediatelyTransitionTo(1f)
}
