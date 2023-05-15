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
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromOccludedTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromOccludedTransitionInteractor::class.simpleName!!) {

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
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (isAbleToDream, keyguardState) = pair
                    if (isAbleToDream && keyguardState == KeyguardState.OCCLUDED) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.OCCLUDED,
                                KeyguardState.DREAMING,
                                getAnimator(),
                            )
                        )
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
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
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
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.OCCLUDED,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(TO_LOCKSCREEN_DURATION),
                            )
                        )
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
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
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
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.OCCLUDED,
                                KeyguardState.GONE,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForOccludedToAodOrDozing() {
        scope.launch {
            keyguardInteractor.wakefulnessModel
                .sample(
                    combine(
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
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
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.OCCLUDED,
                                if (isAodAvailable) {
                                    KeyguardState.AOD
                                } else {
                                    KeyguardState.DOZING
                                },
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForOccludedToAlternateBouncer() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isAlternateBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isAlternateBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.OCCLUDED
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.OCCLUDED,
                                to = KeyguardState.ALTERNATE_BOUNCER,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun getAnimator(duration: Duration = DEFAULT_DURATION): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(duration.inWholeMilliseconds)
        }
    }

    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 933.milliseconds
    }
}
