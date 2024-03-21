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
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaDeviceSession
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.isTheSameSession
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.AudioStreamSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.CastVolumeSliderViewModel
import com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel.SliderViewModel
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
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
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
    private val streamSliderViewModelFactory: AudioStreamSliderViewModel.Factory,
    private val castVolumeSliderViewModelFactory: CastVolumeSliderViewModel.Factory,
) {

    val sliderViewModels: StateFlow<List<SliderViewModel>> =
        combineTransform(
                mediaOutputInteractor.activeMediaDeviceSessions,
                mediaOutputInteractor.defaultActiveMediaSession,
            ) { activeSessions, defaultSession ->
                coroutineScope {
                    val viewModels = buildList {
                        if (defaultSession?.isTheSameSession(activeSessions.remote) == true) {
                            addRemoteViewModelIfNeeded(this, activeSessions.remote)
                            addStreamViewModel(this, AudioManager.STREAM_MUSIC)
                        } else {
                            addStreamViewModel(this, AudioManager.STREAM_MUSIC)
                            addRemoteViewModelIfNeeded(this, activeSessions.remote)
                        }

                        addStreamViewModel(this, AudioManager.STREAM_VOICE_CALL)
                        addStreamViewModel(this, AudioManager.STREAM_RING)
                        addStreamViewModel(this, AudioManager.STREAM_NOTIFICATION)
                        addStreamViewModel(this, AudioManager.STREAM_ALARM)
                    }
                    emit(viewModels)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val mutableIsExpanded = MutableSharedFlow<Boolean>()

    val isExpanded: StateFlow<Boolean> =
        merge(
                mutableIsExpanded,
                mediaOutputInteractor.defaultActiveMediaSession.flatMapLatest {
                    if (it == null) flowOf(true)
                    else mediaDeviceSessionInteractor.playbackState(it).map { it?.isActive != true }
                },
            )
            .stateIn(scope, SharingStarted.Eagerly, false)

    fun onExpandedChanged(isExpanded: Boolean) {
        scope.launch { mutableIsExpanded.emit(isExpanded) }
    }

    private fun CoroutineScope.addRemoteViewModelIfNeeded(
        list: MutableList<SliderViewModel>,
        remoteMediaDeviceSession: MediaDeviceSession?
    ) {
        if (remoteMediaDeviceSession?.canAdjustVolume == true) {
            val viewModel =
                castVolumeSliderViewModelFactory.create(
                    remoteMediaDeviceSession,
                    this,
                )
            list.add(viewModel)
        }
    }

    private fun CoroutineScope.addStreamViewModel(
        list: MutableList<SliderViewModel>,
        stream: Int,
    ) {
        val viewModel =
            streamSliderViewModelFactory.create(
                AudioStreamSliderViewModel.FactoryAudioStreamWrapper(AudioStream(stream)),
                this,
            )
        list.add(viewModel)
    }
}
