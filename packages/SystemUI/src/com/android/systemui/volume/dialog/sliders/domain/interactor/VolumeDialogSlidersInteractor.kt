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

import com.android.systemui.volume.VolumeDialogControllerImpl
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSlidersModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Provides a state for the Sliders section of the Volume Dialog. */
@VolumeDialogScope
class VolumeDialogSlidersInteractor
@Inject
constructor(volumeDialogStateInteractor: VolumeDialogStateInteractor) {

    val sliders: Flow<VolumeDialogSlidersModel> =
        volumeDialogStateInteractor.volumeDialogState.map {
            val sliderTypes: List<VolumeDialogSliderType> =
                it.states.keys.sortedWith(StreamsSorter).map { audioStream ->
                    when {
                        audioStream == VolumeDialogControllerImpl.DYNAMIC_STREAM_BROADCAST ->
                            VolumeDialogSliderType.AudioSharingStream(audioStream)
                        audioStream >=
                            VolumeDialogControllerImpl.DYNAMIC_STREAM_REMOTE_START_INDEX ->
                            VolumeDialogSliderType.RemoteMediaStream(audioStream)
                        else -> VolumeDialogSliderType.Stream(audioStream)
                    }
                }
            VolumeDialogSlidersModel(
                slider = sliderTypes.first(),
                floatingSliders = sliderTypes.drop(1),
            )
        }

    private object StreamsSorter : Comparator<Int> {

        // TODO(b/369992924) order the streams
        override fun compare(lhs: Int, rhs: Int): Int {
            return lhs - rhs
        }
    }
}
