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
import com.android.systemui.keyguard.shared.model.TransitionModeOnCanceled
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromGoneTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.GONE,
    ) {

    override fun start() {
        listenForGoneToAodOrDozing()
        listenForGoneToDreaming()
        listenForGoneToLockscreen()
        listenForGoneToDreamingLockscreenHosted()
    }

    // Primarily for when the user chooses to lock down the device
    private fun listenForGoneToLockscreen() {
        scope.launch {
            keyguardInteractor.isKeyguardShowing
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isKeyguardShowing, lastStartedStep) ->
                    if (isKeyguardShowing && lastStartedStep.to == KeyguardState.GONE) {
                        startTransitionTo(KeyguardState.LOCKSCREEN)
                    }
                }
        }
    }

    private fun listenForGoneToDreamingLockscreenHosted() {
        scope.launch {
            keyguardInteractor.isActiveDreamLockscreenHosted
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isActiveDreamLockscreenHosted, lastStartedStep) ->
                    if (isActiveDreamLockscreenHosted && lastStartedStep.to == KeyguardState.GONE) {
                        startTransitionTo(KeyguardState.DREAMING_LOCKSCREEN_HOSTED)
                    }
                }
        }
    }

    private fun listenForGoneToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isActiveDreamLockscreenHosted,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isAbleToDream, lastStartedStep, isActiveDreamLockscreenHosted) ->
                    if (
                        isAbleToDream &&
                            lastStartedStep.to == KeyguardState.GONE &&
                            !isActiveDreamLockscreenHosted
                    ) {
                        startTransitionTo(KeyguardState.DREAMING)
                    }
                }
        }
    }

    private fun listenForGoneToAodOrDozing() {
        scope.launch {
            powerInteractor.isAsleep
                .sample(
                    combine(
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isAsleep, lastStartedStep, isAodAvailable) ->
                    if (lastStartedStep.to == KeyguardState.GONE && isAsleep) {
                        startTransitionTo(
                            toState =
                                if (isAodAvailable) KeyguardState.AOD else KeyguardState.DOZING,
                            modeOnCanceled = TransitionModeOnCanceled.RESET,
                        )
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.DREAMING -> TO_DREAMING_DURATION
                    KeyguardState.AOD -> TO_AOD_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }
    companion object {
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
        val TO_AOD_DURATION = 1300.milliseconds
    }
}
