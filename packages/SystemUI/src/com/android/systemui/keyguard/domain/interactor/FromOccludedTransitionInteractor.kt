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
import com.android.app.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromOccludedTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.OCCLUDED,
    ) {

    override fun start() {
        listenForOccludedToLockscreen()
        listenForOccludedToDreaming()
        listenForOccludedToAodOrDozing()
        listenForOccludedToGone()
        listenForOccludedToAlternateBouncer()
    }

    private fun listenForOccludedToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(transitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (isAbleToDream, keyguardState) = pair
                    if (isAbleToDream && keyguardState == KeyguardState.OCCLUDED) {
                        startTransitionTo(KeyguardState.DREAMING)
                    }
                }
        }
    }

    private fun listenForOccludedToLockscreen() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(
                    combine(
                        keyguardInteractor.isKeyguardShowing,
                        transitionInteractor.startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isOccluded, isShowing, lastStartedKeyguardState) ->
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    if (
                        !isOccluded &&
                            isShowing &&
                            lastStartedKeyguardState.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.LOCKSCREEN)
                    }
                }
        }
    }

    private fun listenForOccludedToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(
                    combine(
                        keyguardInteractor.isKeyguardShowing,
                        transitionInteractor.startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isOccluded, isShowing, lastStartedKeyguardState) ->
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    if (
                        !isOccluded &&
                            !isShowing &&
                            lastStartedKeyguardState.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForOccludedToAodOrDozing() {
        scope.launch {
            keyguardInteractor.wakefulnessModel
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (wakefulnessState, lastStartedStep, isAodAvailable) ->
                    if (
                        lastStartedStep.to == KeyguardState.OCCLUDED &&
                            wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP
                    ) {
                        startTransitionTo(
                            if (isAodAvailable) KeyguardState.AOD else KeyguardState.DOZING
                        )
                    }
                }
        }
    }

    private fun listenForOccludedToAlternateBouncer() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isAlternateBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isAlternateBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 933.milliseconds
    }
}
