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
import android.media.session.MediaController.PlaybackInfo
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.res.R
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaDeviceSessionInteractor
import com.android.systemui.volume.panel.component.mediaoutput.shared.model.MediaDeviceSession
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CastVolumeSliderViewModel
@AssistedInject
constructor(
    @Assisted private val session: MediaDeviceSession,
    @Assisted private val coroutineScope: CoroutineScope,
    private val context: Context,
    private val mediaDeviceSessionInteractor: MediaDeviceSessionInteractor,
) : SliderViewModel {

    override val slider: StateFlow<SliderState> =
        mediaDeviceSessionInteractor
            .playbackInfo(session)
            .mapNotNull { it?.getCurrentState() }
            .stateIn(coroutineScope, SharingStarted.Eagerly, SliderState.Empty)

    override fun onValueChanged(state: SliderState, newValue: Float) {
        coroutineScope.launch {
            mediaDeviceSessionInteractor.setSessionVolume(session, newValue.roundToInt())
        }
    }

    override fun onValueChangeFinished() {}

    override fun toggleMuted(state: SliderState) {
        // do nothing because this action isn't supported for Cast sliders.
    }

    private fun PlaybackInfo.getCurrentState(): State {
        val volumeRange = 0..maxVolume
        return State(
            value = currentVolume.toFloat(),
            valueRange = volumeRange.first.toFloat()..volumeRange.last.toFloat(),
            icon = Icon.Resource(R.drawable.ic_cast, null),
            label = context.getString(R.string.media_device_cast),
            isEnabled = true,
            a11yStep = 1,
        )
    }

    private data class State(
        override val value: Float,
        override val valueRange: ClosedFloatingPointRange<Float>,
        override val icon: Icon,
        override val label: String,
        override val isEnabled: Boolean,
        override val a11yStep: Int,
    ) : SliderState {
        override val disabledMessage: String?
            get() = null

        override val isMutable: Boolean
            get() = false

        override val a11yClickDescription: String?
            get() = null

        override val a11yStateDescription: String?
            get() = null
    }

    @AssistedFactory
    interface Factory {

        fun create(
            session: MediaDeviceSession,
            coroutineScope: CoroutineScope,
        ): CastVolumeSliderViewModel
    }
}
