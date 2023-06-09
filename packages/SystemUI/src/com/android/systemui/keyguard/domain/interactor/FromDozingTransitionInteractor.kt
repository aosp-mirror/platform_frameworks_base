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
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel.Companion.isWakeAndUnlock
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class FromDozingTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromDozingTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForDozingToLockscreen()
        listenForDozingToGone()
    }

    private fun listenForDozingToLockscreen() {
        scope.launch {
            keyguardInteractor.wakefulnessModel
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (wakefulnessModel, lastStartedTransition) ->
                    if (
                        wakefulnessModel.isStartingToWakeOrAwake() &&
                            lastStartedTransition.to == KeyguardState.DOZING
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DOZING,
                                KeyguardState.LOCKSCREEN,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForDozingToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (biometricUnlockState, lastStartedTransition) ->
                    if (
                        lastStartedTransition.to == KeyguardState.DOZING &&
                            isWakeAndUnlock(biometricUnlockState)
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.DOZING,
                                KeyguardState.GONE,
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
    }
}
