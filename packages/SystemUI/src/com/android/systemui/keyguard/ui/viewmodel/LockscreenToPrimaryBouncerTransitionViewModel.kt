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
import com.android.systemui.keyguard.domain.interactor.FromLockscreenTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.ui.KeyguardTransitionAnimationFlow
import com.android.systemui.keyguard.ui.transitions.DeviceEntryIconTransition
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Breaks down LOCKSCREEN->PRIMARY BOUNCER transition into discrete steps for corresponding views to
 * consume.
 */
@ExperimentalCoroutinesApi
@SysUISingleton
class LockscreenToPrimaryBouncerTransitionViewModel
@Inject
constructor(
    interactor: KeyguardTransitionInteractor,
    shadeDependentFlows: ShadeDependentFlows,
    animationFlow: KeyguardTransitionAnimationFlow,
) : DeviceEntryIconTransition {
    private val transitionAnimation =
        animationFlow.setup(
            duration = FromLockscreenTransitionInteractor.TO_PRIMARY_BOUNCER_DURATION,
            stepFlow =
                interactor.transition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER),
        )

    val shortcutsAlpha: Flow<Float> =
        interactor.transition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER).map {
            1 - it.value
        }

    override val deviceEntryParentViewAlpha: Flow<Float> =
        shadeDependentFlows.transitionFlow(
            flowWhenShadeIsNotExpanded =
                transitionAnimation.sharedFlow(
                    duration = 250.milliseconds,
                    onStep = { 1f - it },
                    onFinish = { 0f }
                ),
            flowWhenShadeIsExpanded = transitionAnimation.immediatelyTransitionTo(0f)
        )
}
