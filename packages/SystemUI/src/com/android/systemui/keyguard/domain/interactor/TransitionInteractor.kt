/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import android.util.Log
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import java.util.UUID

/**
 * Each TransitionInteractor is responsible for determining under which conditions to notify
 * [KeyguardTransitionRepository] to signal a transition. When (and if) the transition occurs is
 * determined by [KeyguardTransitionRepository].
 *
 * [name] field should be a unique identifiable string representing this state, used primarily for
 * logging
 *
 * MUST list implementing classes in dagger module [StartKeyguardTransitionModule] and also in the
 * 'when' clause of [KeyguardTransitionCoreStartable]
 */
sealed class TransitionInteractor(
    val fromState: KeyguardState,
) {
    val name = this::class.simpleName ?: "UnknownTransitionInteractor"

    abstract val transitionRepository: KeyguardTransitionRepository
    abstract val transitionInteractor: KeyguardTransitionInteractor
    abstract fun start()

    fun startTransitionTo(
            toState: KeyguardState,
            animator: ValueAnimator? = getDefaultAnimatorForTransitionsToState(toState),
            resetIfCancelled: Boolean = false
    ): UUID? {
        if (
            fromState != transitionInteractor.startedKeyguardState.value &&
                fromState != transitionInteractor.finishedKeyguardState.value
        ) {
            Log.e(
                name,
                "startTransition: We were asked to transition from " +
                    "$fromState to $toState, however we last finished a transition to " +
                    "${transitionInteractor.finishedKeyguardState.value}, " +
                    "and last started a transition to " +
                    "${transitionInteractor.startedKeyguardState.value}. " +
                    "Ignoring startTransition, but this should never happen."
            )
            return null
        }

        return transitionRepository.startTransition(
            TransitionInfo(
                name,
                fromState,
                toState,
                animator,
            ),
            resetIfCancelled
        )
    }

    /**
     * Returns a ValueAnimator to be used for transitions to [toState], if one is not explicitly
     * passed to [startTransitionTo].
     */
    abstract fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator?
}
