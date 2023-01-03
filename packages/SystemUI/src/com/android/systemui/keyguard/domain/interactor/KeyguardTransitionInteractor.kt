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
import com.android.systemui.keyguard.shared.model.AnimationParams
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.KeyguardState.OCCLUDED
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.math.max
import kotlin.math.min
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

    /** LOCKSCREEN->DREAMING transition information. */
    val lockscreenToDreamingTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, DREAMING)

    /** LOCKSCREEN->OCCLUDED transition information. */
    val lockscreenToOccludedTransition: Flow<TransitionStep> =
        repository.transition(LOCKSCREEN, OCCLUDED)

    /** OCCLUDED->LOCKSCREEN transition information. */
    val occludedToLockscreenTransition: Flow<TransitionStep> =
        repository.transition(OCCLUDED, LOCKSCREEN)

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

    /**
     * Transitions will occur over a [totalDuration] with [TransitionStep]s being emitted in the
     * range of [0, 1]. View animations should begin and end within a subset of this range. This
     * function maps the [startTime] and [duration] into [0, 1], when this subset is valid.
     */
    fun transitionStepAnimation(
        flow: Flow<TransitionStep>,
        params: AnimationParams,
        totalDuration: Duration,
    ): Flow<Float> {
        val start = (params.startTime / totalDuration).toFloat()
        val chunks = (totalDuration / params.duration).toFloat()
        var isRunning = false
        return flow
            .map { step ->
                val value = (step.value - start) * chunks
                if (step.transitionState == STARTED) {
                    // When starting, make sure to always emit. If a transition is started from the
                    // middle, it is possible this animation is being skipped but we need to inform
                    // the ViewModels of the last update
                    isRunning = true
                    max(0f, min(1f, value))
                } else if (isRunning && value >= 1f) {
                    // Always send a final value of 1. Because of rounding, [value] may never be
                    // exactly 1.
                    isRunning = false
                    1f
                } else {
                    value
                }
            }
            .filter { value -> value >= 0f && value <= 1f }
    }
}
