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

package com.android.systemui.communal.ui.viewmodel

import android.graphics.Color
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.Edge
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.util.kotlin.BooleanFlowOperators.allOf
import com.android.systemui.util.kotlin.BooleanFlowOperators.anyOf
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** View model for transitions related to the communal hub. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTransitionViewModel
@Inject
constructor(
    @Application applicationScope: CoroutineScope,
    communalColors: CommunalColors,
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    dreamToGlanceableHubTransitionViewModel: DreamingToGlanceableHubTransitionViewModel,
    glanceableHubToDreamTransitionViewModel: GlanceableHubToDreamingTransitionViewModel,
    communalInteractor: CommunalInteractor,
    private val communalSceneInteractor: CommunalSceneInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    /**
     * Snaps to [CommunalScenes.Communal], showing the glanceable hub immediately without any
     * transition.
     */
    fun snapToCommunal() {
        communalSceneInteractor.snapToScene(
            newScene = CommunalScenes.Communal,
            loggingReason = "transition view model",
        )
    }

    // Show UMO on glanceable hub immediately on transition into glanceable hub
    private val showUmoFromOccludedToGlanceableHub: Flow<Boolean> =
        keyguardTransitionInteractor
            .transition(
                Edge.create(from = KeyguardState.OCCLUDED, to = KeyguardState.GLANCEABLE_HUB)
            )
            .filter {
                (it.transitionState == TransitionState.STARTED ||
                    it.transitionState == TransitionState.CANCELED)
            }
            .map { it.transitionState == TransitionState.STARTED }

    private val showUmoFromGlanceableHubToOccluded: Flow<Boolean> =
        keyguardTransitionInteractor
            .transition(
                edge = Edge.create(from = Scenes.Communal),
                edgeWithoutSceneContainer = Edge.create(from = KeyguardState.GLANCEABLE_HUB),
            )
            .filter {
                it.to == KeyguardState.OCCLUDED &&
                    (it.transitionState == TransitionState.FINISHED ||
                        it.transitionState == TransitionState.CANCELED)
            }
            .map { it.transitionState != TransitionState.FINISHED }

    /**
     * Whether UMO location should be on communal. This flow is responsive to transitions so that a
     * new value is emitted at the right step of a transition to/from communal hub that the location
     * of UMO should be updated.
     */
    val isUmoOnCommunal: Flow<Boolean> =
        anyOf(
                communalSceneInteractor.isIdleOnCommunal,
                allOf(
                    // Only show UMO on the hub if the hub is at least partially visible. This
                    // prevents
                    // the UMO from being missing on the lock screen when going from the hub to lock
                    // screen in some way other than through a direct transition, such as unlocking
                    // from
                    // the hub, then pressing power twice to go back to the lock screen.
                    communalSceneInteractor.isCommunalVisible,
                    // TODO(b/378942852): polish UMO transitions when scene container is enabled
                    if (SceneContainerFlag.isEnabled) flowOf(true)
                    else
                        merge(
                                lockscreenToGlanceableHubTransitionViewModel.showUmo,
                                glanceableHubToLockscreenTransitionViewModel.showUmo,
                                dreamToGlanceableHubTransitionViewModel.showUmo,
                                glanceableHubToDreamTransitionViewModel.showUmo,
                                showUmoFromOccludedToGlanceableHub,
                                showUmoFromGlanceableHubToOccluded,
                            )
                            .onStart { emit(false) },
                ),
            )
            .stateIn(
                scope = applicationScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = false,
            )

    /** Whether to show communal when exiting the occluded state. */
    val showCommunalFromOccluded: Flow<Boolean> = communalInteractor.showCommunalFromOccluded

    val transitionFromOccludedEnded =
        keyguardTransitionInteractor
            .transition(Edge.create(from = KeyguardState.OCCLUDED))
            .filter { step ->
                step.transitionState == TransitionState.FINISHED ||
                    step.transitionState == TransitionState.CANCELED
            }

    val recentsBackgroundColor: Flow<Color?> =
        combine(showCommunalFromOccluded, communalColors.backgroundColor) {
            showCommunalFromOccluded,
            backgroundColor ->
            if (showCommunalFromOccluded) {
                backgroundColor
            } else {
                null
            }
        }
}
