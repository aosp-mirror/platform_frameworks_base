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

package com.android.systemui.volume.dialog.sliders.ui.viewmodel

import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSliderInteractor
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSlidersInteractor
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderViewBinder
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class VolumeDialogSlidersViewModel
@AssistedInject
constructor(
    @VolumeDialog coroutineScope: CoroutineScope,
    private val slidersInteractor: VolumeDialogSlidersInteractor,
    private val sliderInteractorFactory: VolumeDialogSliderInteractor.Factory,
    private val sliderViewModelFactory: VolumeDialogSliderViewModel.Factory,
    private val sliderViewBinderFactory: VolumeDialogSliderViewBinder.Factory,
) {

    val sliders: Flow<VolumeDialogSliderUiModel> =
        slidersInteractor.sliders
            .distinctUntilChanged()
            .map { slidersModel ->
                VolumeDialogSliderUiModel(
                    sliderViewBinder = createSliderViewBinder(slidersModel.slider),
                    floatingSliderViewBinders =
                        slidersModel.floatingSliders.map(::createSliderViewBinder),
                )
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    private fun createSliderViewBinder(type: VolumeDialogSliderType): VolumeDialogSliderViewBinder =
        sliderViewBinderFactory.create {
            sliderViewModelFactory.create(sliderInteractorFactory.create(type))
        }

    @AssistedFactory
    interface Factory {

        fun create(): VolumeDialogSlidersViewModel
    }
}

/** Models slider ui */
data class VolumeDialogSliderUiModel(
    val sliderViewBinder: VolumeDialogSliderViewBinder,
    val floatingSliderViewBinders: List<VolumeDialogSliderViewBinder>,
)
