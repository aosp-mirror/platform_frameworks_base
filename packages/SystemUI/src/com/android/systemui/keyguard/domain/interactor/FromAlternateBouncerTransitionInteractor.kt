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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.util.kotlin.Utils.Companion.toQuad
import com.android.systemui.util.kotlin.Utils.Companion.toQuint
import com.android.systemui.util.kotlin.sample
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@SysUISingleton
class FromAlternateBouncerTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
) : TransitionInteractor(FromAlternateBouncerTransitionInteractor::class.simpleName!!) {

    override fun start() {
        listenForAlternateBouncerToGone()
        listenForAlternateBouncerToLockscreenAodOrDozing()
        listenForAlternateBouncerToPrimaryBouncer()
    }

    private fun listenForAlternateBouncerToLockscreenAodOrDozing() {
        scope.launch {
            keyguardInteractor.alternateBouncerShowing
                // Add a slight delay, as alternateBouncer and primaryBouncer showing event changes
                // will arrive with a small gap in time. This prevents a transition to LOCKSCREEN
                // happening prematurely.
                .onEach { delay(50) }
                .sample(
                    combine(
                        keyguardInteractor.primaryBouncerShowing,
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.wakefulnessModel,
                        keyguardInteractor.isAodAvailable,
                        ::toQuad
                    ),
                    ::toQuint
                )
                .collect {
                    (
                        isAlternateBouncerShowing,
                        isPrimaryBouncerShowing,
                        lastStartedTransitionStep,
                        wakefulnessState,
                        isAodAvailable) ->
                    if (
                        !isAlternateBouncerShowing &&
                            !isPrimaryBouncerShowing &&
                            lastStartedTransitionStep.to == KeyguardState.ALTERNATE_BOUNCER
                    ) {
                        val to =
                            if (
                                wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP ||
                                    wakefulnessState.state == WakefulnessState.ASLEEP
                            ) {
                                if (isAodAvailable) {
                                    KeyguardState.AOD
                                } else {
                                    KeyguardState.DOZING
                                }
                            } else {
                                KeyguardState.LOCKSCREEN
                            }
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.ALTERNATE_BOUNCER,
                                to = to,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForAlternateBouncerToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(keyguardTransitionInteractor.finishedKeyguardState, ::Pair)
                .collect { (isKeyguardGoingAway, keyguardState) ->
                    if (isKeyguardGoingAway && keyguardState == KeyguardState.ALTERNATE_BOUNCER) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.ALTERNATE_BOUNCER,
                                to = KeyguardState.GONE,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForAlternateBouncerToPrimaryBouncer() {
        scope.launch {
            keyguardInteractor.primaryBouncerShowing
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (isPrimaryBouncerShowing, startedKeyguardState) ->
                    if (
                        isPrimaryBouncerShowing &&
                            startedKeyguardState.to == KeyguardState.ALTERNATE_BOUNCER
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.ALTERNATE_BOUNCER,
                                to = KeyguardState.PRIMARY_BOUNCER,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun getAnimator(): ValueAnimator {
        return ValueAnimator().apply {
            interpolator = Interpolators.LINEAR
            duration = TRANSITION_DURATION_MS
        }
    }

    companion object {
        private const val TRANSITION_DURATION_MS = 300L
    }
}
