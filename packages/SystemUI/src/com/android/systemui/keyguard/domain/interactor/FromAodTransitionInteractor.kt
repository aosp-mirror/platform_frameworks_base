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
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class FromAodTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromAodTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForAodToLockscreen()
        listenForAodToGone()
    }

    private fun listenForAodToLockscreen() {
        scope.launch {
            keyguardInteractor
                .dozeTransitionTo(DozeStateModel.FINISH)
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (dozeToAod, lastStartedStep) = pair
                    if (lastStartedStep.to == KeyguardState.AOD) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.AOD,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForAodToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { pair ->
                    val (biometricUnlockState, keyguardState) = pair
                    if (
                        keyguardState == KeyguardState.AOD && isWakeAndUnlock(biometricUnlockState)
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.AOD,
                                KeyguardState.GONE,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            setInterpolator(Interpolators.LINEAR)
            setDuration(TRANSITION_DURATION_MS)
        }
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 500L
    }
}
