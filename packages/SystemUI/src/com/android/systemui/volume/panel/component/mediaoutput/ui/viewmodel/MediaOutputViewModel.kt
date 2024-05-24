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
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputActionsInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.mediaoutput.domain.model.MediaDeviceSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import com.android.systemui.volume.panel.ui.viewmodel.VolumePanelViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Models the UI of the Media Output Volume Panel component. */
@VolumePanelScope
class MediaOutputViewModel
@Inject
constructor(
    private val context: Context,
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val volumePanelViewModel: VolumePanelViewModel,
    private val actionsInteractor: MediaOutputActionsInteractor,
    interactor: MediaOutputInteractor,
) {

    private val mediaDeviceSession: StateFlow<MediaDeviceSession> =
        interactor.mediaDeviceSession.stateIn(
            coroutineScope,
            SharingStarted.Eagerly,
            MediaDeviceSession.Unknown,
        )

    val connectedDeviceViewModel: StateFlow<ConnectedDeviceViewModel?> =
        combine(mediaDeviceSession, interactor.currentConnectedDevice) {
                mediaDeviceSession,
                currentConnectedDevice ->
                ConnectedDeviceViewModel(
                    if (mediaDeviceSession.isPlaying()) {
                        context.getString(
                            R.string.media_output_label_title,
                            (mediaDeviceSession as MediaDeviceSession.Active).appLabel
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
        combine(mediaDeviceSession, interactor.currentConnectedDevice) {
                mediaDeviceSession,
                currentConnectedDevice ->
                if (mediaDeviceSession.isPlaying()) {
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

    private fun MediaDeviceSession.isPlaying(): Boolean =
        this is MediaDeviceSession.Active && playbackState?.isActive == true

    fun onDeviceClick(expandable: Expandable) {
        actionsInteractor.onDeviceClick(expandable)
        volumePanelViewModel.dismissPanel()
    }

    fun onBarClick(expandable: Expandable) {
        actionsInteractor.onBarClick(mediaDeviceSession.value, expandable)
        volumePanelViewModel.dismissPanel()
    }
}
