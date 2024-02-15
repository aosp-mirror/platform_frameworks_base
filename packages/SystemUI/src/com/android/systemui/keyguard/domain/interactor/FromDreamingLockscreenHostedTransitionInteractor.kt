/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@SysUISingleton
class FromDreamingLockscreenHostedTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.DREAMING_LOCKSCREEN_HOSTED,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForDreamingLockscreenHostedToLockscreen()
        listenForDreamingLockscreenHostedToGone()
        listenForDreamingLockscreenHostedToDozing()
        listenForDreamingLockscreenHostedToOccluded()
        listenForDreamingLockscreenHostedToPrimaryBouncer()
    }

    private fun listenForDreamingLockscreenHostedToLockscreen() {
        scope.launch {
            keyguardInteractor.isActiveDreamLockscreenHosted
                // Add a slight delay to prevent transitioning to lockscreen from happening too soon
                // as dozing can arrive in a slight gap after the lockscreen hosted dream stops.
                .onEach { delay(50) }
                .sample(
                    combine(
                        keyguardInteractor.dozeTransitionModel,
                        startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect {
                    (isActiveDreamLockscreenHosted, dozeTransitionModel, lastStartedTransition) ->
                    if (
                        !isActiveDreamLockscreenHosted &&
                            DozeStateModel.isDozeOff(dozeTransitionModel.to) &&
                            lastStartedTransition.to == KeyguardState.DREAMING_LOCKSCREEN_HOSTED
                    ) {
                        startTransitionTo(KeyguardState.LOCKSCREEN)
                    }
                }
        }
    }

    private fun listenForDreamingLockscreenHostedToOccluded() {
        scope.launch {
            keyguardInteractor.isActiveDreamLockscreenHosted
                .sample(
                    combine(
                        keyguardInteractor.isKeyguardOccluded,
                        startedKeyguardTransitionStep,
                        ::Pair,
                    ),
                    ::toTriple
                )
                .collect { (isActiveDreamLockscreenHosted, isOccluded, lastStartedTransition) ->
                    if (
                        isOccluded &&
                            !isActiveDreamLockscreenHosted &&
                            lastStartedTransition.to == KeyguardState.DREAMING_LOCKSCREEN_HOSTED
                    ) {
                        startTransitionTo(KeyguardState.OCCLUDED)
                    }
                }
        }
    }

    private fun listenForDreamingLockscreenHostedToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.DREAMING_LOCKSCREEN_HOSTED
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    private fun listenForDreamingLockscreenHostedToGone() {
        scope.launch {
            keyguardInteractor.biometricUnlockState
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (biometricUnlockState, lastStartedTransitionStep) ->
                    if (
                        lastStartedTransitionStep.to == KeyguardState.DREAMING_LOCKSCREEN_HOSTED &&
                            biometricUnlockState == BiometricUnlockModel.WAKE_AND_UNLOCK_FROM_DREAM
                    ) {
                        startTransitionTo(KeyguardState.GONE)
                    }
                }
        }
    }

    private fun listenForDreamingLockscreenHostedToDozing() {
        scope.launch {
            combine(keyguardInteractor.dozeTransitionModel, startedKeyguardTransitionStep, ::Pair)
                .collect { (dozeTransitionModel, lastStartedTransitionStep) ->
                    if (
                        dozeTransitionModel.to == DozeStateModel.DOZE &&
                            lastStartedTransitionStep.to == KeyguardState.DREAMING_LOCKSCREEN_HOSTED
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
