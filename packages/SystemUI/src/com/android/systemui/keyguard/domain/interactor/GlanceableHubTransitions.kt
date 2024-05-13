/*
 * Copyright (C) 2024 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.keyguard.domain.interactor

import android.animation.ValueAnimator
import com.android.app.animation.Interpolators
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalTransitionProgress
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.util.kotlin.sample
import java.util.UUID
import javax.inject.Inject

class GlanceableHubTransitions
@Inject
constructor(
    private val transitionInteractor: KeyguardTransitionInteractor,
    private val transitionRepository: KeyguardTransitionRepository,
    private val communalInteractor: CommunalInteractor,
) {
    /**
     * Listens for the glanceable hub transition to the specified scene and directly drives the
     * keyguard transition between the lockscreen and the hub.
     *
     * The glanceable hub transition progress is used as the source of truth as it cannot be driven
     * externally. The progress is used for both transitions caused by user touch input or by
     * programmatic changes.
     */
    suspend fun listenForGlanceableHubTransition(
        transitionOwnerName: String,
        fromState: KeyguardState,
        toState: KeyguardState,
    ) {
        // TODO(b/336576536): Check if adaptation for scene framework is needed
        if (SceneContainerFlag.isEnabled) return
        val toScene =
            if (fromState == KeyguardState.GLANCEABLE_HUB) {
                CommunalScenes.Blank
            } else {
                CommunalScenes.Communal
            }
        var transitionId: UUID? = null

        communalInteractor
            .transitionProgressToScene(toScene)
            .sample(
                transitionInteractor.startedKeyguardState,
                ::Pair,
            )
            .collect { (transitionProgress, lastStartedState) ->
                val id = transitionId
                if (id == null) {
                    // No transition started.
                    if (
                        transitionProgress is CommunalTransitionProgress.Transition &&
                            lastStartedState == fromState
                    ) {
                        transitionId =
                            transitionRepository.startTransition(
                                TransitionInfo(
                                    ownerName = transitionOwnerName,
                                    from = fromState,
                                    to = toState,
                                    animator = null, // transition will be manually controlled
                                )
                            )
                    }
                } else {
                    if (lastStartedState != toState) {
                        return@collect
                    }
                    // An existing `id` means a transition is started, and calls to
                    // `updateTransition` will control it until FINISHED or CANCELED
                    val nextState: TransitionState
                    val progressFraction: Float
                    when (transitionProgress) {
                        is CommunalTransitionProgress.Idle -> {
                            if (transitionProgress.scene == toScene) {
                                nextState = TransitionState.FINISHED
                                progressFraction = 1f
                            } else {
                                nextState = TransitionState.CANCELED
                                progressFraction = 0f
                            }
                        }
                        is CommunalTransitionProgress.Transition -> {
                            nextState = TransitionState.RUNNING
                            progressFraction = transitionProgress.progress
                        }
                        is CommunalTransitionProgress.OtherTransition -> {
                            // Shouldn't happen but if another transition starts during the
                            // current one, mark the current one as canceled.
                            nextState = TransitionState.CANCELED
                            progressFraction = 0f
                        }
                    }
                    transitionRepository.updateTransition(
                        id,
                        progressFraction,
                        nextState,
                    )

                    if (
                        nextState == TransitionState.CANCELED ||
                            nextState == TransitionState.FINISHED
                    ) {
                        transitionId = null
                    }

                    // If canceled, just put the state back.
                    if (nextState == TransitionState.CANCELED) {
                        transitionRepository.startTransition(
                            TransitionInfo(
                                ownerName = transitionOwnerName,
                                from = toState,
                                to = fromState,
                                animator =
                                    ValueAnimator().apply {
                                        interpolator = Interpolators.LINEAR
                                        duration = 0
                                    }
                            )
                        )
                    }
                }
            }
    }
}
