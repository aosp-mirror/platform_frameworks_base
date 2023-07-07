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
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromPrimaryBouncerTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardSecurityModel: KeyguardSecurityModel,
) : TransitionInteractor(FromPrimaryBouncerTransitionInteractor::class.simpleName!!) {

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
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
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
                        val to =
                            if (occluded) {
                                KeyguardState.OCCLUDED
                            } else {
                                KeyguardState.LOCKSCREEN
                            }

                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.PRIMARY_BOUNCER,
                                to = to,
                                animator = getAnimator(),
                            )
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
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
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
                        val to =
                            if (isAodAvailable) {
                                KeyguardState.AOD
                            } else {
                                KeyguardState.DOZING
                            }

                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.PRIMARY_BOUNCER,
                                to = to,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForPrimaryBouncerToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
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
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.PRIMARY_BOUNCER,
                                to = KeyguardState.GONE,
                                animator = getAnimator(duration),
                            ),
                            resetIfCanceled = true,
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
        private val DEFAULT_DURATION = 300.milliseconds
        val TO_GONE_DURATION = 250.milliseconds
        val TO_GONE_SHORT_DURATION = 200.milliseconds
    }
}
