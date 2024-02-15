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
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val transitionInteractor: KeyguardTransitionInteractor,
    val mainDispatcher: CoroutineDispatcher,
    val bgDispatcher: CoroutineDispatcher,
) {
    val name = this::class.simpleName ?: "UnknownTransitionInteractor"
    abstract val transitionRepository: KeyguardTransitionRepository
    abstract fun start()

    /* Use background dispatcher for all [KeyguardTransitionInteractor] flows. Necessary because
     * the [sample] utility internally runs a collect on the Unconfined dispatcher, resulting
     * in continuations on the main thread. We don't want that for classes that inherit from this.
     */
    val startedKeyguardTransitionStep =
        transitionInteractor.startedKeyguardTransitionStep.flowOn(bgDispatcher)
    // The following are MutableSharedFlows, and do not require flowOn
    val startedKeyguardState = transitionInteractor.startedKeyguardState
    val finishedKeyguardState = transitionInteractor.finishedKeyguardState
    val currentKeyguardState = transitionInteractor.currentKeyguardState

    suspend fun startTransitionTo(
        toState: KeyguardState,
        animator: ValueAnimator? = getDefaultAnimatorForTransitionsToState(toState),
        modeOnCanceled: TransitionModeOnCanceled = TransitionModeOnCanceled.LAST_VALUE
    ): UUID? {
        if (
            fromState != transitionInteractor.startedKeyguardState.replayCache.last() &&
                fromState != transitionInteractor.finishedKeyguardState.replayCache.last()
        ) {
            Log.e(
                name,
                "startTransition: We were asked to transition from " +
                    "$fromState to $toState, however we last finished a transition to " +
                    "${transitionInteractor.finishedKeyguardState.replayCache.last()}, " +
                    "and last started a transition to " +
                    "${transitionInteractor.startedKeyguardState.replayCache.last()}. " +
                    "Ignoring startTransition, but this should never happen."
            )
            return null
        }
        return withContext(mainDispatcher) {
            transitionRepository.startTransition(
                TransitionInfo(
                    name,
                    fromState,
                    toState,
                    animator,
                    modeOnCanceled,
                )
            )
        }
    }

    /** This signal may come in before the occlusion signal, and can provide a custom transition */
    fun listenForTransitionToCamera(
        scope: CoroutineScope,
        keyguardInteractor: KeyguardInteractor,
    ) {
        scope.launch {
            keyguardInteractor.onCameraLaunchDetected
                .sample(transitionInteractor.finishedKeyguardState)
                .collect { finishedKeyguardState ->
                    // Other keyguard state transitions may trigger on the first power button push,
                    // so use the last finishedKeyguardState to determine the overriding FROM state
                    if (finishedKeyguardState == fromState) {
                        startTransitionTo(
                            toState = KeyguardState.OCCLUDED,
                            modeOnCanceled = TransitionModeOnCanceled.RESET,
                        )
                    }
                }
        }
    }

    /**
     * Returns a ValueAnimator to be used for transitions to [toState], if one is not explicitly
     * passed to [startTransitionTo].
     */
    abstract fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator?
}
