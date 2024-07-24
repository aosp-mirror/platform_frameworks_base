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
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.Utils.Companion.toTriple
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromOccludedTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val powerInteractor: PowerInteractor,
    private val communalInteractor: CommunalInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.OCCLUDED,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForOccludedToLockscreenOrHub()
        listenForOccludedToDreaming()
        listenForOccludedToAodOrDozing()
        listenForOccludedToGone()
        listenForOccludedToAlternateBouncer()
        listenForOccludedToPrimaryBouncer()
    }

    private fun listenForOccludedToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isBouncerShowing, lastStartedTransitionStep) ->
                    if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.OCCLUDED
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    private fun listenForOccludedToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream.sample(finishedKeyguardState, ::Pair).collect {
                (isAbleToDream, keyguardState) ->
                if (isAbleToDream && keyguardState == KeyguardState.OCCLUDED) {
                    startTransitionTo(KeyguardState.DREAMING)
                }
            }
        }
    }

    private fun listenForOccludedToLockscreenOrHub() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(
                    keyguardInteractor.isKeyguardShowing,
                    startedKeyguardTransitionStep,
                    communalInteractor.isIdleOnCommunal,
                )
                .collect { (isOccluded, isShowing, lastStartedKeyguardState, isIdleOnCommunal) ->
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    if (
                        !isOccluded &&
                            isShowing &&
                            lastStartedKeyguardState.to == KeyguardState.OCCLUDED
                    ) {
                        val to =
                            if (isIdleOnCommunal) {
                                KeyguardState.GLANCEABLE_HUB
                            } else {
                                KeyguardState.LOCKSCREEN
                            }
                        startTransitionTo(to)
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
                        startedKeyguardTransitionStep,
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
            powerInteractor.isAsleep
                .sample(
                    combine(
                        startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (isAsleep, lastStartedStep, isAodAvailable) ->
                    if (lastStartedStep.to == KeyguardState.OCCLUDED && isAsleep) {
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
                .sample(startedKeyguardTransitionStep, ::Pair)
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
            interpolator =
                when (toState) {
                    KeyguardState.ALTERNATE_BOUNCER -> Interpolators.FAST_OUT_SLOW_IN
                    else -> Interpolators.LINEAR
                }

            duration =
                when (toState) {
                    KeyguardState.LOCKSCREEN -> TO_LOCKSCREEN_DURATION
                    else -> DEFAULT_DURATION
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromOccludedTransitionInteractor"
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_LOCKSCREEN_DURATION = 933.milliseconds
        val TO_AOD_DURATION = DEFAULT_DURATION
    }
}
