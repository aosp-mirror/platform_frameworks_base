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
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState.KEYGUARD
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@SysUISingleton
class FromLockscreenTransitionInteractor
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    private val shadeRepository: ShadeRepository,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val keyguardTransitionRepository: KeyguardTransitionRepository,
) : TransitionInteractor(FromLockscreenTransitionInteractor::class.simpleName!!) {

    private var transitionId: UUID? = null

    override fun start() {
        listenForLockscreenToGone()
        listenForLockscreenToOccluded()
        listenForLockscreenToAod()
        listenForLockscreenToBouncer()
        listenForLockscreenToDreaming()
        listenForLockscreenToBouncerDragging()
    }

    private fun listenForLockscreenToDreaming() {
        scope.launch {
            keyguardInteractor.isAbleToDream
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isAbleToDream, lastStartedTransition) = pair
                    if (isAbleToDream && lastStartedTransition.to == KeyguardState.LOCKSCREEN) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.LOCKSCREEN,
                                KeyguardState.DREAMING,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForLockscreenToBouncer() {
        scope.launch {
            keyguardInteractor.isBouncerShowing
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isBouncerShowing, lastStartedTransitionStep) = pair
                    if (
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
                }
        }
    }

    /* Starts transitions when manually dragging up the bouncer from the lockscreen. */
    private fun listenForLockscreenToBouncerDragging() {
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

    private fun listenForLockscreenToGone() {
        scope.launch {
            keyguardInteractor.isKeyguardGoingAway
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (isKeyguardGoingAway, lastStartedStep) = pair
                    if (isKeyguardGoingAway && lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.LOCKSCREEN,
                                KeyguardState.GONE,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForLockscreenToOccluded() {
        scope.launch {
            keyguardInteractor.isKeyguardOccluded
                .sample(
                    combine(
                        keyguardTransitionInteractor.finishedKeyguardState,
                        keyguardInteractor.isDreaming,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { triple ->
                    val (isOccluded, keyguardState, isDreaming) = triple
                    // Occlusion signals come from the framework, and should interrupt any
                    // existing transition
                    if (isOccluded && !isDreaming) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                keyguardState,
                                KeyguardState.OCCLUDED,
                                getAnimator(),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForLockscreenToAod() {
        scope.launch {
            keyguardInteractor
                .dozeTransitionTo(DozeStateModel.DOZE_AOD)
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { pair ->
                    val (dozeToAod, lastStartedStep) = pair
                    if (lastStartedStep.to == KeyguardState.LOCKSCREEN) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.LOCKSCREEN,
                                KeyguardState.AOD,
                                getAnimator(),
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
        private const val TRANSITION_DURATION_MS = 500L
    }
}
