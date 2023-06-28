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
import com.android.systemui.animation.Interpolators
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromGoneTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromGoneTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForGoneToAodOrDozing()
        listenForGoneToDreaming()
        listenForGoneToLockscreen()
    }

    // Primarily for when the user chooses to lock down the device
    private fun listenForGoneToLockscreen() {
        scope.launch {
            keyguardInteractor.isKeyguardShowing
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isKeyguardShowing, lastStartedStep) ->
                    if (isKeyguardShowing && lastStartedStep.to == KeyguardState.GONE) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.GONE,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForGoneToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isAbleToDream, lastStartedStep) ->
                    if (isAbleToDream && lastStartedStep.to == KeyguardState.GONE) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.GONE,
                                KeyguardState.DREAMING,
                                getAnimator(TO_DREAMING_DURATION),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForGoneToAodOrDozing() {
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
                        lastStartedStep.to == KeyguardState.GONE &&
                            wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.GONE,
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

    private fun getAnimator(duration: Duration = DEFAULT_DURATION): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(duration.inWholeMilliseconds)
        }
    }

    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
    }
}
