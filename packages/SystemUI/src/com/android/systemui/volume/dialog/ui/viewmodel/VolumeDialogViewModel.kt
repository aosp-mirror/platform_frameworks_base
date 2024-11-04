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

package com.android.systemui.volume.dialog.ui.viewmodel

import android.content.Context
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import com.android.systemui.volume.dialog.shared.model.streamLabel
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSliderInteractor
import com.android.systemui.volume.dialog.sliders.domain.interactor.VolumeDialogSlidersInteractor
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Provides a state for the Volume Dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
class VolumeDialogViewModel
@AssistedInject
constructor(
    private val context: Context,
    dialogVisibilityInteractor: VolumeDialogVisibilityInteractor,
    volumeDialogSlidersInteractor: VolumeDialogSlidersInteractor,
    private val volumeDialogSliderInteractorFactory: VolumeDialogSliderInteractor.Factory,
) {

    val dialogVisibilityModel: Flow<VolumeDialogVisibilityModel> =
        dialogVisibilityInteractor.dialogVisibility
    val dialogTitle: Flow<String> =
        volumeDialogSlidersInteractor.sliders.flatMapLatest { slidersModel ->
            val interactor = volumeDialogSliderInteractorFactory.create(slidersModel.slider)
            interactor.slider.map { sliderModel ->
                context.getString(R.string.volume_dialog_title, sliderModel.streamLabel(context))
            }
        }

    @AssistedFactory
    interface Factory {
        fun create(): VolumeDialogViewModel
    }
}
