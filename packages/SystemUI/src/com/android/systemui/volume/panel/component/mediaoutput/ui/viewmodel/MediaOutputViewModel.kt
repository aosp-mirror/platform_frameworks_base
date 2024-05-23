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

package com.android.systemui.volume.panel.component.mediaoutput.ui.viewmodel

import android.content.Context
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Color
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.domain.interactor.AudioOutputInteractor
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputActionsInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.SessionWithPlaybackState
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.shared.model.Result
import com.android.systemui.volume.panel.shared.model.filterData
import com.android.systemui.volume.panel.shared.model.wrapInResult
import com.android.systemui.volume.panel.ui.VolumePanelUiEvent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** Models the UI of the Media Output Volume Panel component. */
@OptIn(ExperimentalCoroutinesApi::class)
@VolumePanelScope
class MediaOutputViewModel
@Inject
constructor(
    private val context: Context,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val actionsInteractor: MediaOutputActionsInteractor,
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
    private val audioOutputInteractor: AudioOutputInteractor,
    audioModeInteractor: AudioModeInteractor,
    interactor: MediaOutputInteractor,
    private val uiEventLogger: UiEventLogger,
) {

    private val sessionWithPlaybackState: StateFlow<Result<SessionWithPlaybackState?>> =
        interactor.defaultActiveMediaSession
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

    val connectedDeviceViewModel: StateFlow<ConnectedDeviceViewModel?> =
        combine(
                sessionWithPlaybackState.filterData(),
                audioModeInteractor.isOngoingCall,
                audioOutputInteractor.currentAudioDevice.filter {
                    it !is AudioOutputDevice.Unknown
                },
                audioOutputInteractor.isInAudioSharing,
            ) { mediaDeviceSession, isOngoingCall, currentConnectedDevice, isInAudioSharing ->
                val label =
                    when {
                        isOngoingCall -> context.getString(R.string.media_output_title_ongoing_call)
                        mediaDeviceSession?.isPlaybackActive == true ->
                            context.getString(
                                R.string.media_output_label_title,
                                mediaDeviceSession.session.appLabel
                            )
                        else -> context.getString(R.string.media_output_title_without_playing)
                    }
                ConnectedDeviceViewModel(
                    label,
                    when (isInAudioSharing) {
                        true -> {
                            context.getString(R.string.audio_sharing_description)
                        }
                        false -> {
                            currentConnectedDevice.name
                        }
                    }
                )
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                null,
            )

    val deviceIconViewModel: StateFlow<DeviceIconViewModel?> =
        combine(sessionWithPlaybackState.filterData(), audioOutputInteractor.currentAudioDevice) {
                mediaDeviceSession,
                currentConnectedDevice ->
                val icon: Icon =
                    currentConnectedDevice
                        .takeIf { currentConnectedDevice !is AudioOutputDevice.Unknown }
                        ?.icon
                        ?.let { Icon.Loaded(it, null) }
                        ?: Icon.Resource(R.drawable.ic_media_home_devices, null)
                if (mediaDeviceSession?.isPlaybackActive == true) {
                    DeviceIconViewModel.IsPlaying(
                        icon = icon,
                        iconColor =
                            Color.Attribute(com.android.internal.R.attr.materialColorSurface),
                        backgroundColor =
                            Color.Attribute(com.android.internal.R.attr.materialColorSecondary),
                    )
                } else {
                    DeviceIconViewModel.IsNotPlaying(
                        icon = icon,
                        iconColor =
                            Color.Attribute(
                                com.android.internal.R.attr.materialColorOnSurfaceVariant
                            ),
                        backgroundColor =
                            Color.Attribute(com.android.internal.R.attr.materialColorSurface),
                    )
                }
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                null,
            )

    val enabled: StateFlow<Boolean> =
        audioOutputInteractor.isInAudioSharing
            .map { !it }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                true,
            )

    fun onBarClick(expandable: Expandable?) {
        uiEventLogger.log(VolumePanelUiEvent.VOLUME_PANEL_MEDIA_OUTPUT_CLICKED)
        val result = sessionWithPlaybackState.value
        actionsInteractor.onBarClick((result as? Result.Data)?.data, expandable)
    }
}
