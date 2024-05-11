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
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenToGlanceableHubTransitionViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/** View model for transitions related to the communal hub. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalTransitionViewModel
@Inject
constructor(
    communalColors: CommunalColors,
    glanceableHubToLockscreenTransitionViewModel: GlanceableHubToLockscreenTransitionViewModel,
    lockscreenToGlanceableHubTransitionViewModel: LockscreenToGlanceableHubTransitionViewModel,
    dreamToGlanceableHubTransitionViewModel: DreamingToGlanceableHubTransitionViewModel,
    glanceableHubToDreamTransitionViewModel: GlanceableHubToDreamingTransitionViewModel,
    communalInteractor: CommunalInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
) {
    // Show UMO on glanceable hub immediately on transition into glanceable hub
    private val showUmoFromOccludedToGlanceableHub: Flow<Boolean> =
        keyguardTransitionInteractor
            .transitionStepsFromState(KeyguardState.OCCLUDED)
            .filter {
                it.to == KeyguardState.GLANCEABLE_HUB &&
                    (it.transitionState == TransitionState.STARTED ||
                        it.transitionState == TransitionState.CANCELED)
            }
            .map { it.transitionState == TransitionState.STARTED }

    private val showUmoFromGlanceableHubToOccluded: Flow<Boolean> =
        keyguardTransitionInteractor
            .transitionStepsFromState(KeyguardState.GLANCEABLE_HUB)
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
        merge(
                lockscreenToGlanceableHubTransitionViewModel.showUmo,
                glanceableHubToLockscreenTransitionViewModel.showUmo,
                dreamToGlanceableHubTransitionViewModel.showUmo,
                glanceableHubToDreamTransitionViewModel.showUmo,
                showUmoFromOccludedToGlanceableHub,
                showUmoFromGlanceableHubToOccluded,
            )
            .distinctUntilChanged()

    /** Whether to show communal when exiting the occluded state. */
    val showCommunalFromOccluded: Flow<Boolean> = communalInteractor.showCommunalFromOccluded

    val transitionFromOccludedEnded =
        keyguardTransitionInteractor.transitionStepsFromState(KeyguardState.OCCLUDED).filter { step
            ->
            step.transitionState == TransitionState.FINISHED ||
                step.transitionState == TransitionState.CANCELED
        }

    val recentsBackgroundColor: Flow<Color?> =
        combine(showCommunalFromOccluded, communalColors.backgroundColor) {
            showCommunalFromOccluded,
            backgroundColor,
            ->
            if (showCommunalFromOccluded) {
                backgroundColor
            } else {
                null
            }
        }
}
