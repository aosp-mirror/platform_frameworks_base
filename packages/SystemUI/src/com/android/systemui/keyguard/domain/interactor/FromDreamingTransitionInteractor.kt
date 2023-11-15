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
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromDreamingTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DREAMING,
    ) {

    override fun start() {
        listenForDreamingToOccluded()
        listenForDreamingToGone()
        listenForDreamingToDozing()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    fun startToLockscreenTransition() {
        scope.launch {
            if (
                transitionInteractor.startedKeyguardState.replayCache.last() ==
                    KeyguardState.DREAMING
            ) {
                startTransitionTo(KeyguardState.LOCKSCREEN)
            }
        }
    }

    private fun listenForDreamingToOccluded() {
        scope.launch {
            combine(keyguardInteractor.isKeyguardOccluded, keyguardInteractor.isDreaming, ::Pair)
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::toTriple)
                .collect { (isOccluded, isDreaming, lastStartedTransition) ->
                    if (
                        isOccluded &&
                            !isDreaming &&
                            lastStartedTransition.to == KeyguardState.DREAMING
                    ) {
                        startTransitionTo(KeyguardState.OCCLUDED)
                    }
                }
        }
    }

    private fun listenForDreamingToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (biometricUnlockState, lastStartedTransitionStep) ->
                    if (
                        lastStartedTransitionStep.to == KeyguardState.DREAMING &&
                            biometricUnlockState == BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForDreamingToDozing() {
        scope.launch {
            combine(
                    keyguardInteractor.dozeTransitionModel,
                    transitionInteractor.finishedKeyguardState,
                    ::Pair
                )
                .collect { (dozeTransitionModel, keyguardState) ->
                    if (
                        dozeTransitionModel.to == DozeStateModel.DOZE &&
                            keyguardState == KeyguardState.DREAMING
                    ) {
                        startTransitionTo(KeyguardState.DOZING)
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                if (toState == KeyguardState.LOCKSCREEN) TO_LOCKSCREEN_DURATION.inWholeMilliseconds
                else DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 1167.milliseconds
    }
}
