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
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
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
    /** (any)->GONE transition information */
    val anyStateToGoneTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == KeyguardState.GONE }

    /** (any)->AOD transition information */
    val anyStateToAodTransition: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.to == KeyguardState.AOD }

    /** AOD->LOCKSCREEN transition information. */
    val aodToLockscreenTransition: Flow<TransitionStep> = repository.transition(AOD, LOCKSCREEN)

    /** DREAMING->LOCKSCREEN transition information. */
    val dreamingToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(DREAMING, LOCKSCREEN)

    /** GONE->DREAMING transition information. */
    val goneToDreamingTransition: Flow<TransitionStep> = repository.transition(GONE, DREAMING)

    /** LOCKSCREEN->AOD transition information. */
    val lockscreenToAodTransition: Flow<TransitionStep> = repository.transition(LOCKSCREEN, AOD)

    /** LOCKSCREEN->PRIMARY_BOUNCER transition information. */
    val mLockscreenToPrimaryBouncerTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, PRIMARY_BOUNCER)

    /** LOCKSCREEN->DREAMING transition information. */
    val lockscreenToDreamingTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, DREAMING)

    /** LOCKSCREEN->OCCLUDED transition information. */
    val lockscreenToOccludedTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, OCCLUDED)

    /** OCCLUDED->LOCKSCREEN transition information. */
    val occludedToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(OCCLUDED, LOCKSCREEN)

    /** PRIMARY_BOUNCER->GONE transition information. */
    val primaryBouncerToGoneTransition: Flow<TransitionStep> =
        repository.transition(PRIMARY_BOUNCER, GONE)

    /**
     * AOD<->LOCKSCREEN transition information, mapped to dozeAmount range of AOD (1f) <->
     * Lockscreen (0f).
     */
    val dozeAmountTransition: Flow<TransitionStep> =
        merge(
            aodToLockscreenTransition.map { step -> step.copy(value = 1f - step.value) },
            lockscreenToAodTransition,
        )

    /* The last [TransitionStep] with a [TransitionState] of STARTED */
    val startedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.STARTED }

    /* The last [TransitionStep] with a [TransitionState] of CANCELED */
    val canceledKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.CANCELED }

    /* The last [TransitionStep] with a [TransitionState] of FINISHED */
    val finishedKeyguardTransitionStep: Flow<TransitionStep> =
        repository.transitions.filter { step -> step.transitionState == TransitionState.FINISHED }

    /* The last completed [KeyguardState] transition */
    val finishedKeyguardState: Flow<KeyguardState> =
        finishedKeyguardTransitionStep.map { step -> step.to }
}
