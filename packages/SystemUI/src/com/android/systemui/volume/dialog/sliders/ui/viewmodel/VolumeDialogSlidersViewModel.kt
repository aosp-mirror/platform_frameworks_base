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
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.sliders.dagger.VolumeDialogSliderComponent
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSlidersInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@VolumeDialogScope
class VolumeDialogSlidersViewModel
@Inject
constructor(
    @VolumeDialog coroutineScope: CoroutineScope,
    private val slidersInteractor: VolumeDialogSlidersInteractor,
    private val sliderComponentFactory: VolumeDialogSliderComponent.Factory,
) {

    val sliders: Flow<VolumeDialogSliderUiModel> =
        slidersInteractor.sliders
            .map { slidersModel ->
                VolumeDialogSliderUiModel(
                    sliderComponent = sliderComponentFactory.create(slidersModel.slider),
                    floatingSliderComponent =
                        slidersModel.floatingSliders.map(sliderComponentFactory::create),
                )
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()
}

/** Models slider ui */
data class VolumeDialogSliderUiModel(
    val sliderComponent: VolumeDialogSliderComponent,
    val floatingSliderComponent: List<VolumeDialogSliderComponent>,
)
