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

import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import com.android.systemui.volume.panel.component.volume.domain.interactor.AudioSlidersInteractor
import com.android.systemui.volume.panel.component.volume.domain.model.SliderType
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.CastVolumeSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.filterData
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

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
    mediaOutputInteractor: MediaOutputInteractor,
    mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
    private val streamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    private val castVolumeSliderViewModelFactory: CastVolumeSliderViewModel.Factory,
    audioModeInteractor: AudioModeInteractor,
    streamsInteractor: AudioSlidersInteractor,
) {

    private val mutableIsExpanded = MutableStateFlow<Boolean?>(null)
    private val isActive: Flow<Boolean?> =
        combine(
                audioModeInteractor.isOngoingCall,
                mediaOutputInteractor.defaultActiveMediaSession.filterData().flatMapLatest { session
                    ->
                    if (session == null) {
                        flowOf(false)
                    } else {
                        mediaDeviceSessionInteractor.playbackState(session).map {
                            it?.isActive == true
                        }
                    }
                },
            ) { isOngoingCall, isPlaybackActive ->
                isOngoingCall || isPlaybackActive
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val portraitExpandable: Flow<SlidersExpandableViewModel> =
        isActive
            .filterNotNull()
            .flatMapLatest { isActive ->
                if (isActive) {
                    mutableIsExpanded.filterNotNull().map { isExpanded ->
                        SlidersExpandableViewModel.Expandable(isExpanded)
                    }
                } else {
                    flowOf(SlidersExpandableViewModel.Fixed)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, SlidersExpandableViewModel.Unavailable)

    val sliderViewModels: StateFlow<List<SliderViewModel>> =
        streamsInteractor.volumePanelSliders
            .transformLatest { sliderTypes ->
                coroutineScope {
                    val viewModels =
                        sliderTypes.map { type ->
                            when (type) {
                                is SliderType.Stream -> createStreamViewModel(type.stream)
                                is SliderType.MediaDeviceCast ->
                                    createSessionViewModel(type.session)
                            }
                        }
                    emit(viewModels)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        isActive.filterNotNull().onEach { mutableIsExpanded.value = !it }.launchIn(scope)
    }

    fun isExpandable(isPortrait: Boolean): Flow<SlidersExpandableViewModel> {
        return if (isPortrait) {
            portraitExpandable
        } else {
            flowOf(SlidersExpandableViewModel.Fixed)
        }
    }

    fun onExpandedChanged(isExpanded: Boolean) {
        scope.launch { mutableIsExpanded.value = isExpanded }
    }

    private fun CoroutineScope.createSessionViewModel(
        session: MediaDeviceSession
    ): CastVolumeSliderViewModel {
        return castVolumeSliderViewModelFactory.create(session, this)
    }

    private fun CoroutineScope.createStreamViewModel(
        stream: AudioStream,
    ): AudioStreamSliderViewModel {
        return streamSliderViewModelFactory.create(
            AudioStreamSliderViewModel.FactoryAudioStreamWrapper(stream),
            this,
        )
    }
}
