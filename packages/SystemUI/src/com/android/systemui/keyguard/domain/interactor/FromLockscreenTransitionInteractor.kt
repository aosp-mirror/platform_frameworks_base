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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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

    override fun start() {
        listenForLockscreenToGone()
        listenForLockscreenToOccluded()
        listenForLockscreenToCamera()
        listenForLockscreenToAodOrDozing()
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
                                getAnimator(TO_DREAMING_DURATION),
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
        var transitionId: UUID? = null
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
                .collect { (shadeModel, keyguardState, statusBarState) ->
                    val id = transitionId
                    if (id != null) {
                        // An existing `id` means a transition is started, and calls to
                        // `updateTransition` will control it until FINISHED or CANCELED
                        var nextState =
                            if (shadeModel.expansionAmount == 0f) {
                                TransitionState.FINISHED
                            } else if (shadeModel.expansionAmount == 1f) {
                                TransitionState.CANCELED
                            } else {
                                TransitionState.RUNNING
                            }
                        keyguardTransitionRepository.updateTransition(
                            id,
                            1f - shadeModel.expansionAmount,
                            nextState,
                        )

                        if (
                            nextState == TransitionState.CANCELED ||
                                nextState == TransitionState.FINISHED
                        ) {
                            transitionId = null
                        }

                        // If canceled, just put the state back
                        if (nextState == TransitionState.CANCELED) {
                            keyguardTransitionRepository.startTransition(
                                TransitionInfo(
                                    ownerName = name,
                                    from = KeyguardState.BOUNCER,
                                    to = KeyguardState.LOCKSCREEN,
                                    animator = getAnimator(0.milliseconds)
                                )
                            )
                        }
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
                .collect { (isOccluded, keyguardState, isDreaming) ->
                    if (isOccluded && !isDreaming && keyguardState == KeyguardState.LOCKSCREEN) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                keyguardState,
                                KeyguardState.OCCLUDED,
                                getAnimator(TO_OCCLUDED_DURATION),
                            )
                        )
                    }
                }
        }
    }

    /** This signal may come in before the occlusion signal, and can provide a custom transition */
    private fun listenForLockscreenToCamera() {
        scope.launch {
            keyguardInteractor.onCameraLaunchDetected
                .sample(keyguardTransitionInteractor.startedKeyguardTransitionStep, ::Pair)
                .collect { (_, lastStartedStep) ->
                    // DREAMING/AOD/OFF may trigger on the first power button push, so include this
                    // state in order to cancel and correct the transition
                    if (
                        lastStartedStep.to == KeyguardState.LOCKSCREEN ||
                            lastStartedStep.to == KeyguardState.DREAMING ||
                            lastStartedStep.to == KeyguardState.DOZING ||
                            lastStartedStep.to == KeyguardState.AOD ||
                            lastStartedStep.to == KeyguardState.OFF
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.LOCKSCREEN,
                                KeyguardState.OCCLUDED,
                                getAnimator(TO_OCCLUDED_DURATION),
                            )
                        )
                    }
                }
        }
    }

    private fun listenForLockscreenToAodOrDozing() {
        scope.launch {
            keyguardInteractor.wakefulnessModel
                .sample(
                    combine(
                        keyguardTransitionInteractor.startedKeyguardTransitionStep,
                        keyguardInteractor.isAodAvailable,
                        ::Pair
                    ),
                    ::toTriple
                )
                .collect { (wakefulnessState, lastStartedStep, isAodAvailable) ->
                    if (
                        lastStartedStep.to == KeyguardState.LOCKSCREEN &&
                            wakefulnessState.state == WakefulnessState.STARTING_TO_SLEEP
                    ) {
                        keyguardTransitionRepository.startTransition(
                            TransitionInfo(
                                name,
                                KeyguardState.LOCKSCREEN,
                                if (isAodAvailable) {
                                    KeyguardState.AOD
                                } else {
                                    KeyguardState.DOZING
                                },
                                getAnimator(),
                            )
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
        private val DEFAULT_DURATION = 500.milliseconds
        val TO_DREAMING_DURATION = 933.milliseconds
        val TO_OCCLUDED_DURATION = 450.milliseconds
    }
}
