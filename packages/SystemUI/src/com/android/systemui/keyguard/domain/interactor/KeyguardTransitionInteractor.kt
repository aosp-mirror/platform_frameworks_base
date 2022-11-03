/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** Encapsulates business-logic related to the keyguard transitions. */
@SysUISingleton
class KeyguardTransitionInteractor
@Inject
constructor(
    repository: KeyguardTransitionRepository,
) {
    /** AOD->LOCKSCREEN transition information. */
    val aodToLockscreenTransition: Flow<TransitionStep> = repository.transition(AOD, LOCKSCREEN)

    /** LOCKSCREEN->AOD transition information. */
    val lockscreenToAodTransition: Flow<TransitionStep> = repository.transition(LOCKSCREEN, AOD)

    /** (any)->AOD transition information */
    val anyStateToAodTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == KeyguardState.AOD }

    /**
     * AOD<->LOCKSCREEN transition information, mapped to dozeAmount range of AOD (1f) <->
     * Lockscreen (0f).
     */
    val dozeAmountTransition: Flow<TransitionStep> =
        merge(
            aodToLockscreenTransition.map { step -> step.copy(value = 1f - step.value) },
            lockscreenToAodTransition,
        )

    /* The last [TransitionStep] with a [TransitionState] of FINISHED */
    val finishedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.FINISHED }

    /* The last completed [KeyguardState] transition */
    val finishedKeyguardState: Flow<KeyguardState> =
        finishedKeyguardTransitionStep.map { step -> step.to }

    /* The last [TransitionStep] with a [TransitionState] of STARTED */
    val startedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.STARTED }
}
