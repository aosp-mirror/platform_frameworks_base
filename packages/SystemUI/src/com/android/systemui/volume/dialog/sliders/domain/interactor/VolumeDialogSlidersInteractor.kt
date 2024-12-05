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

import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioSystem
import com.android.settingslib.flags.Flags
import com.android.systemui.volume.VolumeDialogControllerImpl
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogStateInteractor
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStateModel
import com.android.systemui.volume.dialog.shared.model.VolumeDialogStreamModel
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSliderType
import com.android.systemui.volume.dialog.sliders.domain.model.VolumeDialogSlidersModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.stateIn

private const val DEFAULT_STREAM = AudioManager.STREAM_MUSIC

/** Provides a state for the Sliders section of the Volume Dialog. */
@VolumeDialogScope
class VolumeDialogSlidersInteractor
@Inject
constructor(
    volumeDialogStateInteractor: VolumeDialogStateInteractor,
    private val packageManager: PackageManager,
    @VolumeDialog private val coroutineScope: CoroutineScope,
) {

    private val streamsSorter = StreamsSorter()
    val sliders: Flow<VolumeDialogSlidersModel> =
        volumeDialogStateInteractor.volumeDialogState
            .filter { it.streamModels.isNotEmpty() }
            .map { stateModel ->
                val sliderTypes =
                    stateModel.streamModels.values
                        .filter { streamModel -> shouldShowSliders(stateModel, streamModel) }
                        .sortedWith(streamsSorter)
                        .map { model -> model.toType() }
                LinkedHashSet(sliderTypes)
            }
            .runningReduce { sliderTypes, newSliderTypes ->
                newSliderTypes.apply { addAll(sliderTypes) }
            }
            .map { sliderTypes ->
                VolumeDialogSlidersModel(
                    slider = sliderTypes.first(),
                    floatingSliders = sliderTypes.drop(1),
                )
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, null)
            .filterNotNull()

    private fun shouldShowSliders(
        stateModel: VolumeDialogStateModel,
        streamModel: VolumeDialogStreamModel,
    ): Boolean {
        if (streamModel.isActive) {
            return true
        }

        if (!packageManager.isTv()) {
            if (streamModel.stream == AudioSystem.STREAM_ACCESSIBILITY) {
                return stateModel.shouldShowA11ySlider
            }

            // Always show the stream for audio sharing if it exists.
            if (
                (Flags.volumeDialogAudioSharingFix() || Flags.audioSharingDeveloperOption()) &&
                    streamModel.stream == VolumeDialogControllerImpl.DYNAMIC_STREAM_BROADCAST
            ) {
                return true
            }

            return streamModel.stream == DEFAULT_STREAM || streamModel.isDynamic
        }

        return false
    }

    private fun VolumeDialogStreamModel.toType(): VolumeDialogSliderType {
        return when {
            stream == VolumeDialogControllerImpl.DYNAMIC_STREAM_BROADCAST ->
                VolumeDialogSliderType.AudioSharingStream(stream)
            stream >= VolumeDialogControllerImpl.DYNAMIC_STREAM_REMOTE_START_INDEX ->
                VolumeDialogSliderType.RemoteMediaStream(stream)
            else -> VolumeDialogSliderType.Stream(stream)
        }
    }

    private class StreamsSorter : Comparator<VolumeDialogStreamModel> {

        /**
         * This list reflects the order of the sorted collection. Elements that satisfy predicates
         * at the beginning of this list will be earlier in the sorted collection.
         */
        private val priorityPredicates: List<(VolumeDialogStreamModel) -> Boolean> =
            listOf(
                { it.isActive },
                { it.stream == AudioManager.STREAM_MUSIC },
                { it.stream == AudioManager.STREAM_ACCESSIBILITY },
                { it.stream == AudioManager.STREAM_RING },
                { it.stream == AudioManager.STREAM_NOTIFICATION },
                { it.stream == AudioManager.STREAM_VOICE_CALL },
                { it.stream == AudioManager.STREAM_SYSTEM },
                { it.isDynamic },
            )

        override fun compare(lhs: VolumeDialogStreamModel, rhs: VolumeDialogStreamModel): Int {
            return lhs.getPriority() - rhs.getPriority()
        }

        private fun VolumeDialogStreamModel.getPriority(): Int {
            val index = priorityPredicates.indexOfFirst { it(this) }
            return if (index >= 0) {
                index
            } else {
                stream
            }
        }
    }
}

private fun PackageManager.isTv(): Boolean = hasSystemFeature(PackageManager.FEATURE_LEANBACK)
