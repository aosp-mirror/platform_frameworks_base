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

package com.android.systemui.volume.panel.component.volume.slider.ui.viewmodel

import android.content.Context
import com.android.settingslib.volume.domain.model.RoutingSession
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.component.volume.domain.interactor.CastVolumeInteractor
import com.android.systemui.volume.panel.component.volume.domain.interactor.VolumeSliderInteractor
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CastVolumeSliderViewModel
@AssistedInject
constructor(
    @Assisted private val routingSession: RoutingSession,
    @Assisted private val coroutineScope: CoroutineScope,
    private val context: Context,
    mediaOutputInteractor: MediaOutputInteractor,
    private val volumeSliderInteractor: VolumeSliderInteractor,
    private val castVolumeInteractor: CastVolumeInteractor,
) : SliderViewModel {

    private val volumeRange = 0..routingSession.routingSessionInfo.volumeMax
    private val value = MutableStateFlow(0f)

    override val slider: StateFlow<SliderState> =
        combine(value, mediaOutputInteractor.currentConnectedDevice) { value, _ ->
                getCurrentState(value)
            }
            .stateIn(coroutineScope, SharingStarted.Eagerly, getCurrentState(value.value))

    override fun onValueChangeFinished(state: SliderState, newValue: Float) {
        coroutineScope.launch {
            value.value = newValue
            castVolumeInteractor.setVolume(
                routingSession,
                volumeSliderInteractor.translateValueToVolume(newValue, volumeRange),
            )
        }
    }

    private fun getCurrentState(value: Float): State {
        return State(
            value =
                volumeSliderInteractor.processVolumeToValue(
                    volume = routingSession.routingSessionInfo.volume,
                    volumeRange = volumeRange,
                    currentValue = value,
                    isMuted = false,
                ),
            valueRange = volumeSliderInteractor.displayValueRange,
            icon = Icon.Resource(R.drawable.ic_cast, null),
            label = context.getString(R.string.media_device_cast),
            isEnabled = true,
        )
    }

    private data class State(
        override val value: Float,
        override val valueRange: ClosedFloatingPointRange<Float>,
        override val icon: Icon,
        override val label: String,
        override val isEnabled: Boolean,
    ) : SliderState {
        override val disabledMessage: String?
            get() = null
    }

    @AssistedFactory
    interface Factory {

        fun create(
            routingSession: RoutingSession,
            coroutineScope: CoroutineScope,
        ): CastVolumeSliderViewModel
    }
}
