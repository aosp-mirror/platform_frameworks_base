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
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeStateModel.Companion.isDozeOff
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromDreamingTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromDreamingTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForDreamingToLockscreen()
        listenForDreamingToOccluded()
        listenForDreamingToGone()
        listenForDreamingToDozing()
    }

    private fun listenForDreamingToLockscreen() {
        scope.launch {
            // Using isDreamingWithOverlay provides an optimized path to LOCKSCREEN state, which
            // otherwise would have gone through OCCLUDED first
            keyguardInteractor.isDreamingWithOverlay
                .sample(
                    combine(
                        keyguardInteractor.dozeTransitionModel,
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isDreaming, dozeTransitionModel, lastStartedTransition) = triple
                    if (
                        !isDreaming &&
                            isDozeOff(dozeTransitionModel.to) &&
                            lastStartedTransition.to == KeyguardState.DREAMING
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(TO_LOCKSCREEN_DURATION),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToOccluded() {
        scope.launch {
            keyguardInteractor.isDreaming
                .sample(
                    combine(
                        keyguardInteractor.isKeyguardOccluded,
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        ::Pair,
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isDreaming, isOccluded, lastStartedTransition) = triple
                    if (
                        isOccluded &&
                            !isDreaming &&
                            (lastStartedTransition.to == KeyguardState.DREAMING ||
                                lastStartedTransition.to == KeyguardState.LOCKSCREEN)
                    ) {
                        // At the moment, checking for LOCKSCREEN state above provides a corrective
                        // action. There's no great signal to determine when the dream is ending
                        // and a transition to OCCLUDED is beginning directly. For now, the solution
                        // is DREAMING->LOCKSCREEN->OCCLUDED
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                lastStartedTransition.to,
                                KeyguardState.OCCLUDED,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (biometricUnlockState, keyguardState) = pair
                    if (
                        keyguardState == KeyguardState.DREAMING &&
                            isWakeAndUnlock(biometricUnlockState)
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.GONE,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDreamingToDozing() {
        scope.launch {
            combine(
                    keyguardInteractor.dozeTransitionModel,
                    keyguardTransitionInteractor.finishedKeyguardState,
                    ::Pair
                )
                .collect { pair ->
                    val (dozeTransitionModel, keyguardState) = pair
                    if (
                        dozeTransitionModel.to == DozeStateModel.DOZE &&
                            keyguardState == KeyguardState.DREAMING
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DREAMING,
                                KeyguardState.DOZING,
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
        val TO_LOCKSCREEN_DURATION = 1183.milliseconds
    }
}
