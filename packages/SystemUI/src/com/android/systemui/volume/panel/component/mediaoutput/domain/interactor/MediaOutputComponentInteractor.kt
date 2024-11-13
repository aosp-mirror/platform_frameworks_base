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

package com.android.systemui.volume.panel.component.mediaoutput.domain.interactor

import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.volume.domain.interactor.AudioOutputInteractor
import com.android.systemui.volume.domain.interactor.AudioSharingInteractor
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaOutputComponentModel
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.SessionWithPlaybackState
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.Result
import com.android.systemui.volume.panel.shared.model.filterData
import com.android.systemui.volume.panel.shared.model.wrapInResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** Gathers together a domain state for the Media Output Volume Panel component. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class MediaOutputComponentInteractor
@Inject
constructor(
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
    audioOutputInteractor: AudioOutputInteractor,
    audioModeInteractor: AudioModeInteractor,
    mediaOutputInteractor: MediaOutputInteractor,
    audioSharingInteractor: AudioSharingInteractor,
) {

    private val sessionWithPlaybackState: StateFlow<Result<SessionWithPlaybackState?>> =
        mediaOutputInteractor.defaultActiveMediaSession
            .filterData()
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null)
                } else {
                    mediaDeviceSessionInteractor.playbackState(session).mapNotNull { playback ->
                        playback?.let { SessionWithPlaybackState(session, playback.isActive) }
                    }
                }
            }
            .wrapInResult()
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                Result.Loading(),
            )

    private val currentAudioDevice: Flow<AudioOutputDevice> =
        audioOutputInteractor.currentAudioDevice.filter { it !is AudioOutputDevice.Unavailable }

    /**
     * Model for the Media Output component in the Volume Panel. It's guaranteed to have an
     * available device if it's loaded.
     */
    val mediaOutputModel: StateFlow<Result<MediaOutputComponentModel>> =
        combine(
                audioSharingInteractor.isInAudioSharing,
                audioModeInteractor.isOngoingCall,
                currentAudioDevice,
            ) { isInAudioSharing, isOngoingCall, currentAudioDevice ->
                if (isOngoingCall) {
                    flowOf(
                        MediaOutputComponentModel.Calling(
                            device = currentAudioDevice,
                            isInAudioSharing = isInAudioSharing,
                            canOpenAudioSwitcher = false,
                        )
                    )
                } else {
                    sessionWithPlaybackState.filterData().map { sessionWithPlaybackState ->
                        if (sessionWithPlaybackState == null) {
                            MediaOutputComponentModel.Idle(
                                device = currentAudioDevice,
                                isInAudioSharing = isInAudioSharing,
                                canOpenAudioSwitcher =
                                    !isInAudioSharing &&
                                        currentAudioDevice !is AudioOutputDevice.Unknown,
                            )
                        } else {
                            MediaOutputComponentModel.MediaSession(
                                session = sessionWithPlaybackState.session,
                                isPlaybackActive = sessionWithPlaybackState.isPlaybackActive,
                                device = currentAudioDevice,
                                isInAudioSharing = isInAudioSharing,
                                canOpenAudioSwitcher =
                                    !isInAudioSharing &&
                                        currentAudioDevice !is AudioOutputDevice.Unknown,
                            )
                        }
                    }
                }
            }
            .flatMapLatest { it }
            .wrapInResult()
            .stateIn(coroutineScope, SharingStarted.Eagerly, Result.Loading())
}
