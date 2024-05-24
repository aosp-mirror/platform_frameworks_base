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

package com.android.systemui.volume.panel.component.volume.ui.viewmodel

import android.media.AudioManager
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.volume.panel.component.volume.domain.interactor.CastVolumeInteractor
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.CastVolumeSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Controls the behaviour of the whole audio
 * [com.android.systemui.volume.panel.shared.model.VolumePanelUiComponent].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class AudioVolumeComponentViewModel
@Inject
constructor(
    @VolumePanelScope private val scope: CoroutineScope,
    castVolumeInteractor: CastVolumeInteractor,
    private val streamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    private val castVolumeSliderViewModelFactory: CastVolumeSliderViewModel.Factory,
) {

    private val remoteSessionsViewModels: Flow<List<SliderViewModel>> =
        castVolumeInteractor.remoteRoutingSessions.transformLatest { routingSessions ->
            coroutineScope {
                emit(
                    routingSessions.map { routingSession ->
                        castVolumeSliderViewModelFactory.create(routingSession, this)
                    }
                )
            }
        }
    private val streamViewModels: Flow<List<SliderViewModel>> =
        flowOf(
                listOf(
                    AudioStream(AudioManager.STREAM_MUSIC),
                    AudioStream(AudioManager.STREAM_VOICE_CALL),
                    AudioStream(AudioManager.STREAM_RING),
                    AudioStream(AudioManager.STREAM_NOTIFICATION),
                    AudioStream(AudioManager.STREAM_ALARM),
                )
            )
            .transformLatest { streams ->
                coroutineScope {
                    emit(
                        streams.map { stream ->
                            streamSliderViewModelFactory.create(
                                AudioStreamSliderViewModel.FactoryAudioStreamWrapper(stream),
                                this,
                            )
                        }
                    )
                }
            }

    val sliderViewModels: StateFlow<List<SliderViewModel>> =
        combine(remoteSessionsViewModels, streamViewModels) {
                remoteSessionsViewModels,
                streamViewModels ->
                remoteSessionsViewModels + streamViewModels
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
