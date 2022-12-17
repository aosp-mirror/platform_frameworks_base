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
import com.android.systemui.keyguard.shared.model.StatusBarState.KEYGUARD
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
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
class LockscreenBouncerTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeRepository: ShadeRepository,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor
) : TransitionInteractor(LockscreenBouncerTransitionInteractor::class.simpleName!!) {

    private var transitionId: UUID? = null

    override fun start() {
        listenForDraggingUpToBouncer()
        listenForBouncer()
    }

    private fun listenForBouncer() {
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
                    } else if (
                        isBouncerShowing && lastStartedTransitionStep.to == KeyguardState.LOCKSCREEN
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = name,
                                from = KeyguardState.LOCKSCREEN,
                                to = KeyguardState.BOUNCER,
                                animator = getAnimator(),
                            )
                        )
                    }
                    Unit
                }
        }
    }

    /* Starts transitions when manually dragging up the bouncer from the lockscreen. */
    private fun listenForDraggingUpToBouncer() {
        scope.launch {
            shadeRepository.shadeModel
                .sample(
                    combine(
                        keyguardTransitionInteractor.finishedKeyguardState,
                        keyguardInteractor.statusBarState,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (shadeModel, keyguardState, statusBarState) = triple

                    val id = transitionId
                    if (id != null) {
                        // An existing `id` means a transition is started, and calls to
                        // `updateTransition` will control it until FINISHED
                        keyguardTransitionRepository.updateTransition(
                            id,
                            1f - shadeModel.expansionAmount,
                            if (
                                shadeModel.expansionAmount == 0f || shadeModel.expansionAmount == 1f
                            ) {
                                transitionId = null
                                TransitionState.FINISHED
                            } else {
                                TransitionState.RUNNING
                            }
                        )
                    } else {
                        // TODO (b/251849525): Remove statusbarstate check when that state is
                        // integrated into KeyguardTransitionRepository
                        if (
                            keyguardState == KeyguardState.LOCKSCREEN &&
                                shadeModel.isUserDragging &&
                                statusBarState == KEYGUARD
                        ) {
                            transitionId =
                                keyguardTransitionRepository.startTransition(
                                    TransitionInfo(
                                        ownerName = name,
                                        from = KeyguardState.LOCKSCREEN,
                                        to = KeyguardState.BOUNCER,
                                        animator = null,
                                    )
                                )
                        }
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
