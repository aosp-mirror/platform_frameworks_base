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

package com.android.systemui.dreams.ui.viewmodel

import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dock.DockManager
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DreamViewModel
@Inject
constructor(
    configurationInteractor: ConfigurationInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    fromGlanceableHubTransitionInteractor: GlanceableHubToDreamingTransitionViewModel,
    private val toGlanceableHubTransitionViewModel: DreamingToGlanceableHubTransitionViewModel,
    private val toLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
    private val dockManager: DockManager,
    private val communalInteractor: CommunalInteractor,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val userTracker: UserTracker,
) {

    fun startTransitionFromDream() {
        val showGlanceableHub =
            dockManager.isDocked &&
                communalInteractor.isCommunalEnabled.value &&
                !keyguardUpdateMonitor.isEncryptedOrLockdown(userTracker.userId)
        if (showGlanceableHub) {
            toGlanceableHubTransitionViewModel.startTransition()
            communalInteractor.onSceneChanged(CommunalScenes.Communal)
        } else {
            toLockscreenTransitionViewModel.startTransition()
        }
    }

    val dreamOverlayTranslationX: Flow<Float> =
        merge(
                toGlanceableHubTransitionViewModel.dreamOverlayTranslationX,
                fromGlanceableHubTransitionInteractor.dreamOverlayTranslationX,
            )
            .distinctUntilChanged()

    val dreamOverlayTranslationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.dream_overlay_exit_y_offset)
            .flatMapLatest { px: Int ->
                toLockscreenTransitionViewModel.dreamOverlayTranslationY(px)
            }

    val dreamAlpha: Flow<Float> =
        merge(
                toLockscreenTransitionViewModel.dreamOverlayAlpha,
                toGlanceableHubTransitionViewModel.dreamAlpha,
            )
            .distinctUntilChanged()

    val dreamOverlayAlpha: Flow<Float> =
        merge(
                toLockscreenTransitionViewModel.dreamOverlayAlpha,
                toGlanceableHubTransitionViewModel.dreamOverlayAlpha,
                fromGlanceableHubTransitionInteractor.dreamOverlayAlpha,
            )
            .distinctUntilChanged()

    val transitionEnded =
        keyguardTransitionInteractor.fromDreamingTransition.filter { step ->
            step.transitionState == TransitionState.FINISHED ||
                step.transitionState == TransitionState.CANCELED
        }
}
