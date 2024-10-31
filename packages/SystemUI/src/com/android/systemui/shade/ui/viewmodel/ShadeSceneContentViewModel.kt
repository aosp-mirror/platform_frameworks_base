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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade.ui.viewmodel

import androidx.lifecycle.LifecycleOwner
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
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
import com.android.systemui.unfold.domain.interactor.UnfoldTransitionInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Models UI state used to render the content of the shade scene.
 *
 * Different from [ShadeUserActionsViewModel], which only models user actions that can be performed
 * to navigate to other scenes.
 */
class ShadeSceneContentViewModel
@AssistedInject
constructor(
    val qsSceneAdapter: QSSceneAdapter,
    val shadeHeaderViewModelFactory: ShadeHeaderViewModel.Factory,
    val brightnessMirrorViewModelFactory: BrightnessMirrorViewModel.Factory,
    val mediaCarouselInteractor: MediaCarouselInteractor,
    shadeInteractor: ShadeInteractor,
    private val footerActionsViewModelFactory: FooterActionsViewModel.Factory,
    private val footerActionsController: FooterActionsController,
    private val unfoldTransitionInteractor: UnfoldTransitionInteractor,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val sceneInteractor: SceneInteractor,
) : ExclusiveActivatable() {

    val shadeMode: StateFlow<ShadeMode> = shadeInteractor.shadeMode

    private val _isEmptySpaceClickable =
        MutableStateFlow(!deviceEntryInteractor.isDeviceEntered.value)
    /** Whether clicking on the empty area of the shade does something */
    val isEmptySpaceClickable: StateFlow<Boolean> = _isEmptySpaceClickable.asStateFlow()

    val isMediaVisible: StateFlow<Boolean> = mediaCarouselInteractor.hasActiveMediaOrRecommendation

    private val footerActionsControllerInitialized = AtomicBoolean(false)

    override suspend fun onActivated(): Nothing {
        deviceEntryInteractor.isDeviceEntered.collect { isDeviceEntered ->
            _isEmptySpaceClickable.value = !isDeviceEntered
        }
    }

    /**
     * Amount of X-axis translation to apply to various elements as the unfolded foldable is folded
     * slightly, in pixels.
     */
    fun unfoldTranslationX(isOnStartSide: Boolean): Flow<Float> {
        return unfoldTransitionInteractor.unfoldTranslationX(isOnStartSide)
    }

    fun getFooterActionsViewModel(lifecycleOwner: LifecycleOwner): FooterActionsViewModel {
        if (footerActionsControllerInitialized.compareAndSet(false, true)) {
            footerActionsController.init()
        }
        return footerActionsViewModelFactory.create(lifecycleOwner)
    }

    /** Notifies that the empty space in the shade has been clicked. */
    fun onEmptySpaceClicked() {
        if (!isEmptySpaceClickable.value) {
            return
        }

        sceneInteractor.changeScene(Scenes.Lockscreen, "Shade empty space clicked.")
    }

    @AssistedFactory
    interface Factory {
        fun create(): ShadeSceneContentViewModel
    }
}
