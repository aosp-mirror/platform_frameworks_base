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
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.Password
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromPrimaryBouncerTransitionInteractor
@Inject
constructor(
    override val transitionRepository: KeyguardTransitionRepository,
    override val transitionInteractor: KeyguardTransitionInteractor,
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardSecurityModel: KeyguardSecurityModel,
) :
    TransitionInteractor(
        fromState = KeyguardState.PRIMARY_BOUNCER,
    ) {

    override fun start() {
        listenForPrimaryBouncerToGone()
        listenForPrimaryBouncerToAodOrDozing()
        listenForPrimaryBouncerToLockscreenOrOccluded()
    }

    private fun listenForPrimaryBouncerToLockscreenOrOccluded() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(
                    combine(
                        keyguardInteractor.wakefulnessModel,
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isKeyguardOccluded,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect { (isBouncerShowing, wakefulnessState, lastStartedTransitionStep, occluded)
                    ->
                    if (
                        !isBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER &&
                            (wakefulnessState.state == WakefulnessState.AWAKE ||
                                wakefulnessState.state == WakefulnessState.STARTING_TO_WAKE)
                    ) {
                        startTransitionTo(
                            if (occluded) KeyguardState.OCCLUDED else KeyguardState.LOCKSCREEN
                        )
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToAodOrDozing() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(
                    combine(
                        keyguardInteractor.wakefulnessModel,
                        transitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Triple
                    ),
                    ::toQuad
                )
                .collect {
                    (isBouncerShowing, wakefulnessState, lastStartedTransitionStep, isAodAvailable)
                    ->
                    if (
                        !isBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER &&
                            (wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP ||
                                wakefulnessState.state == WakefulnessState.ASLEEP)
                    ) {
                        startTransitionTo(
                            if (isAodAvailable) KeyguardState.AOD else KeyguardState.DOZING
                        )
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(transitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isKeyguardGoingAway, lastStartedTransitionStep) ->
                    if (
                        isKeyguardGoingAway &&
                            lastStartedTransitionStep.to == KeyguardState.PRIMARY_BOUNCER
                    ) {
                        val securityMode =
                            keyguardSecurityModel.getSecurityMode(
                                KeyguardUpdateMonitor.getCurrentUser()
                            )
                        // IME for password requires a slightly faster animation
                        val duration =
                            if (securityMode == Password) {
                                TO_GONE_SHORT_DURATION
                            } else {
                                TO_GONE_DURATION
                            }

                        startTransitionTo(
                            toState = KeyguardState.GONE,
                            animator =
                                getDefaultAnimatorForTransitionsToState(KeyguardState.GONE).apply {
                                    this.duration = duration.inWholeMilliseconds
                                },
                            resetIfCancelled = true
                        )
                    }
                }
        }
    }

    override fun getDefaultAnimatorForTransitionsToState(toState: KeyguardState): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = DEFAULT_DURATION.inWholeMilliseconds
        }
    }

    companion object {
        private val DEFAULT_DURATION = 300.milliseconds
        val TO_GONE_DURATION = 250.milliseconds
        val TO_GONE_SHORT_DURATION = 200.milliseconds
    }
}
