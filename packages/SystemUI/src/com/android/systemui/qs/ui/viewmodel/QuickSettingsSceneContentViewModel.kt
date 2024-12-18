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
 * limitations under the License.
 */

package com.android.systemui.qs.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.media.controls.domain.pipeline.interactor.MediaCarouselInteractor
import com.android.systemui.qs.FooterActionsController
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel
import com.android.systemui.qs.ui.adapter.QSSceneAdapter
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.brightness.ui.viewModel.BrightnessMirrorViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Models UI state needed for rendering the content of the quick settings scene.
 *
 * Different from [QuickSettingsUserActionsViewModel] that models the UI state needed to figure out
 * which user actions can trigger navigation to other scenes.
 */
class QuickSettingsSceneContentViewModel
@AssistedInject
constructor(
    val brightnessMirrorViewModelFactory: BrightnessMirrorViewModel.Factory,
    val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    val qsSceneAdapter: QSSceneAdapter,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    val mediaCarouselInteractor: MediaCarouselInteractor,
    private val shadeInteractor: ShadeInteractor,
    private val sceneInteractor: SceneInteractor,
) : ExclusiveActivatable() {

    val isMediaVisible: StateFlow<Boolean> = mediaCarouselInteractor.hasAnyMediaOrRecommendation

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }

    override suspend fun onActivated(): Nothing {
        coroutineScope {
            launch {
                shadeInteractor.shadeMode.collect { shadeMode ->
                    if (shadeMode == ShadeMode.Split) {
                        sceneInteractor.snapToScene(Scenes.Shade, "Unfold while on QS")
                    }
                }
            }
            awaitCancellation()
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): QuickSettingsSceneContentViewModel
    }
}
