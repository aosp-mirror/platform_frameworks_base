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

import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.viewmodel.DreamingToGlanceableHubTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.DreamingToLockscreenTransitionViewModel
import com.android.systemui.keyguard.ui.viewmodel.GlanceableHubToDreamingTransitionViewModel
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.merge

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class DreamOverlayViewModel
@Inject
constructor(
    configurationInteractor: ConfigurationInteractor,
    toGlanceableHubTransitionViewModel: DreamingToGlanceableHubTransitionViewModel,
    fromGlanceableHubTransitionInteractor: GlanceableHubToDreamingTransitionViewModel,
    private val toLockscreenTransitionViewModel: DreamingToLockscreenTransitionViewModel,
) {

    val dreamOverlayTranslationX: Flow<Float> =
        merge(
            toGlanceableHubTransitionViewModel.dreamOverlayTranslationX,
            fromGlanceableHubTransitionInteractor.dreamOverlayTranslationX,
        )

    val dreamOverlayTranslationY: Flow<Float> =
        configurationInteractor
            .dimensionPixelSize(R.dimen.dream_overlay_exit_y_offset)
            .flatMapLatest { px: Int ->
                toLockscreenTransitionViewModel.dreamOverlayTranslationY(px)
            }

    val dreamOverlayAlpha: Flow<Float> =
        merge(
            toLockscreenTransitionViewModel.dreamOverlayAlpha,
            toGlanceableHubTransitionViewModel.dreamOverlayAlpha,
            fromGlanceableHubTransitionInteractor.dreamOverlayAlpha,
        )

    val transitionEnded = toLockscreenTransitionViewModel.transitionEnded
}
