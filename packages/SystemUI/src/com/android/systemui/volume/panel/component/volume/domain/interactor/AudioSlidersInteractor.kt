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

package com.android.systemui.volume.panel.component.volume.domain.interactor

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.isTheSameSession
import com.android.systemui.volume.panel.component.volume.domain.model.SliderType
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.stateIn

/** Provides volume sliders to show in the Volume Panel. */
@VolumePanelScope
class AudioSlidersInteractor
@Inject
constructor(
    @VolumePanelScope scope: CoroutineScope,
    mediaOutputInteractor: MediaOutputInteractor,
    audioRepository: AudioRepository,
) {

    val volumePanelSliders: StateFlow<List<SliderType>> =
        combineTransform(
                mediaOutputInteractor.activeMediaDeviceSessions,
                mediaOutputInteractor.defaultActiveMediaSession,
                audioRepository.communicationDevice,
            ) { activeSessions, defaultSession, communicationDevice ->
                coroutineScope {
                    val viewModels = buildList {
                        if (defaultSession?.isTheSameSession(activeSessions.remote) == true) {
                            addSession(activeSessions.remote)
                            addStream(AudioManager.STREAM_MUSIC)
                        } else {
                            addStream(AudioManager.STREAM_MUSIC)
                            addSession(activeSessions.remote)
                        }

                        if (communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                            addStream(AudioManager.STREAM_BLUETOOTH_SCO)
                        } else {
                            addStream(AudioManager.STREAM_VOICE_CALL)
                        }
                        addStream(AudioManager.STREAM_RING)
                        addStream(AudioManager.STREAM_NOTIFICATION)
                        addStream(AudioManager.STREAM_ALARM)
                    }
                    emit(viewModels)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun MutableList<SliderType>.addSession(remoteMediaDeviceSession: MediaDeviceSession?) {
        if (remoteMediaDeviceSession?.canAdjustVolume == true) {
            add(SliderType.MediaDeviceCast(remoteMediaDeviceSession))
        }
    }

    private fun MutableList<SliderType>.addStream(stream: Int) {
        add(SliderType.Stream(AudioStream(stream)))
    }
}
