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
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromBouncerTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeRepository: ShadeRepository,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor
) : TransitionInteractor(FromBouncerTransitionInteractor::class.simpleName!!) {

    private var transitionId: UUID? = null

    override fun start() {
        listenForBouncerToGone()
        listenForBouncerToLockscreenOrAod()
    }

    private fun listenForBouncerToLockscreenOrAod() {
        scope.launch {
            keyguardInteractor.isBouncerShowing
                .sample(
                    combine(
                        keyguardInteractor.wakefulnessModel,
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isBouncerShowing, wakefulnessState, lastStartedTransitionStep) = triple
                    if (
                        !isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.BOUNCER
                    ) {
                        val to =
                            if (
                                wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP ||
                                    wakefulnessState.state == WakefulnessState.ASLEEP
                            ) {
                                KeyguardState.AOD
                            } else {
                                KeyguardState.LOCKSCREEN
                            }
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.BOUNCER,
                                to = to,
                                animator = getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForBouncerToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(keyguardTransitionInteractor.finishedKeyguardState, { a, b -> Pair(a, b) })
                .collect { pair ->
                    val (isKeyguardGoingAway, keyguardState) = pair
                    if (isKeyguardGoingAway && keyguardState == KeyguardState.BOUNCER) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.BOUNCER,
                                to = KeyguardState.GONE,
                                animator = getAnimator(),
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
        private const val TRANSITION_DURATION_MS = 300L
    }
}
