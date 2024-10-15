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

package com.android.systemui.volume.dialog.sliders.domain.interactor

import com.android.systemui.plugins.VolumeDialogController
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/** Operates a state of particular slider of the Volume Dialog. */
class VolumeDialogSliderInteractor
@AssistedInject
constructor(
    @Assisted private val sliderType: VolumeDialogSliderType,
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    private val volumeDialogController: VolumeDialogController,
) {

    val slider: Flow<VolumeDialogStreamModel> =
        volumeDialogStateInteractor.volumeDialogState.mapNotNull {
            it.streamModels[sliderType.audioStream]
        }

    fun setStreamVolume(userLevel: Int) {
        volumeDialogController.setStreamVolume(sliderType.audioStream, userLevel)
    }

    @VolumeDialogScope
    @AssistedFactory
    interface Factory {

        fun create(sliderType: VolumeDialogSliderType): VolumeDialogSliderInteractor
    }
}
