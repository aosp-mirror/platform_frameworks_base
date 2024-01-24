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
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.util.kotlin.Utils.Companion.sample
import com.android.systemui.util.kotlin.sample
import com.android.wm.shell.animation.Interpolators
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@SysUISingleton
class FromAlternateBouncerTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    transitionInteractor: KeyguardTransitionInteractor,
    @Background private val scope: CoroutineScope,
    @Background bgDispatcher: CoroutineDispatcher,
    @Main mainDispatcher: CoroutineDispatcher,
    private val keyguardInteractor: KeyguardInteractor,
    private val communalInteractor: CommunalInteractor,
    private val powerInteractor: PowerInteractor,
) :
    TransitionInteractor(
        fromState = KeyguardState.ALTERNATE_BOUNCER,
        transitionInteractor = transitionInteractor,
        mainDispatcher = mainDispatcher,
        bgDispatcher = bgDispatcher,
    ) {

    override fun start() {
        listenForAlternateBouncerToGone()
        listenForAlternateBouncerToLockscreenHubAodOrDozing()
        listenForAlternateBouncerToPrimaryBouncer()
        listenForTransitionToCamera(scope, keyguardInteractor)
    }

    private fun listenForAlternateBouncerToLockscreenHubAodOrDozing() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                // Add a slight delay, as alternateBouncer and primaryBouncer showing event changes
                // will arrive with a small gap in time. This prevents a transition to LOCKSCREEN
                // happening prematurely.
                .onEach { delay(50) }
                .sample(
                    keyguardInteractor.primaryBouncerShowing,
                    startedKeyguardTransitionStep,
                    powerInteractor.isAwake,
                    keyguardInteractor.isAodAvailable,
                    communalInteractor.isIdleOnCommunal
                )
                .collect {
                    (
                        isAlternateBouncerShowing,
                        isPrimaryBouncerShowing,
                        lastStartedTransitionStep,
                        isAwake,
                        isAodAvailable,
                        isIdleOnCommunal) ->
                    if (
                        !isAlternateBouncerShowing &&
                            !isPrimaryBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.ALTERNATE_BOUNCER
                    ) {
                        val to =
                            if (!isAwake) {
                                if (isAodAvailable) {
                                    KeyguardState.AOD
                                } else {
                                    KeyguardState.DOZING
                                }
                            } else {
                                if (isIdleOnCommunal) {
                                    KeyguardState.GLANCEABLE_HUB
                                } else {
                                    KeyguardState.LOCKSCREEN
                                }
                            }
                        startTransitionTo(to)
                    }
                }
        }
    }

    private fun listenForAlternateBouncerToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway.sample(finishedKeyguardState, ::Pair).collect {
                (isKeyguardGoingAway, keyguardState) ->
                if (isKeyguardGoingAway && keyguardState == KeyguardState.ALTERNATE_BOUNCER) {
                    startTransitionTo(KeyguardState.GONE)
                }
            }
        }
    }

    private fun listenForAlternateBouncerToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(startedKeyguardTransitionStep, ::Pair)
                .collect { (isPrimaryBouncerShowing, startedKeyguardState) ->
                    if (
                        isPrimaryBouncerShowing &&
                            startedKeyguardState.to == KeyguardState.ALTERNATE_BOUNCER
                    ) {
                        startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration =
                when (toState) {
                    KeyguardState.GONE -> TO_GONE_DURATION
                    else -> TRANSITION_DURATION_MS
                }.inWholeMilliseconds
        }
    }

    companion object {
        const val TAG = "FromAlternateBouncerTransitionInteractor"
        val TRANSITION_DURATION_MS = 300.milliseconds
        val TO_GONE_DURATION = 500.milliseconds
        val TO_AOD_DURATION = TRANSITION_DURATION_MS
        val TO_PRIMARY_BOUNCER_DURATION = TRANSITION_DURATION_MS
    }
}
