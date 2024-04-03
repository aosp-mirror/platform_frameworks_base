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
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.Color
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputActionsInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.SessionWithPlayback
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    interactor: MediaOutputInteractor,
) {

    private val sessionWithPlayback: StateFlow<SessionWithPlayback?> =
        interactor.defaultActiveMediaSession
            .flatMapLatest { session ->
                if (session == null) {
                    flowOf(null)
                } else {
                    mediaDeviceSessionInteractor.playbackState(session).map { playback ->
                        playback?.let { SessionWithPlayback(session, it) }
                    }
                }
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                null,
            )

    val connectedDeviceViewModel: StateFlow<ConnectedDeviceViewModel?> =
        combine(sessionWithPlayback, interactor.currentConnectedDevice) {
                mediaDeviceSession,
                currentConnectedDevice ->
                ConnectedDeviceViewModel(
                    if (mediaDeviceSession?.playback?.isActive == true) {
                        context.getString(
                            R.string.media_output_label_title,
                            mediaDeviceSession.session.appLabel
                        )
                    } else {
                        context.getString(R.string.media_output_title_without_playing)
                    },
                    currentConnectedDevice?.name,
                )
            }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                null,
            )

    val deviceIconViewModel: StateFlow<DeviceIconViewModel?> =
        combine(sessionWithPlayback, interactor.currentConnectedDevice) {
                mediaDeviceSession,
                currentConnectedDevice ->
                if (mediaDeviceSession?.playback?.isActive == true) {
                    val icon =
                        currentConnectedDevice?.icon?.let { Icon.Loaded(it, null) }
                            ?: Icon.Resource(
                                com.android.internal.R.drawable.ic_bt_headphones_a2dp,
                                null
                            )
                    DeviceIconViewModel.IsPlaying(
                        icon = icon,
                        iconColor =
                            Color.Attribute(com.android.internal.R.attr.materialColorSurface),
                        backgroundColor =
                            Color.Attribute(com.android.internal.R.attr.materialColorSecondary),
                    )
                } else {
                    DeviceIconViewModel.IsNotPlaying(
                        icon = Icon.Resource(R.drawable.ic_media_home_devices, null),
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

    fun onBarClick(expandable: Expandable) {
        actionsInteractor.onBarClick(sessionWithPlayback.value, expandable)
    }
}
