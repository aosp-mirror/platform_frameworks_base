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

package com.android.systemui.bluetooth.qsdialog

import androidx.annotation.StringRes
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.res.R
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

sealed class AudioSharingButtonState {
    object Gone : AudioSharingButtonState()

    data class Visible(@StringRes val resId: Int, val isActive: Boolean) :
        AudioSharingButtonState()
}

class AudioSharingButtonViewModel
@AssistedInject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    private val audioSharingInteractor: AudioSharingInteractor,
    private val bluetoothStateInteractor: BluetoothStateInteractor,
    private val deviceItemInteractor: DeviceItemInteractor,
) : ExclusiveActivatable() {

    private val mutableButtonState =
        MutableStateFlow<AudioSharingButtonState>(AudioSharingButtonState.Gone)
    /** Flow representing the update of AudioSharingButtonState. */
    val audioSharingButtonStateUpdate: StateFlow<AudioSharingButtonState> =
        mutableButtonState.asStateFlow()

    override suspend fun onActivated(): Nothing {
        combine(
                bluetoothStateInteractor.bluetoothStateUpdate,
                deviceItemInteractor.deviceItemUpdate,
                audioSharingInteractor.isAudioSharingOn
            ) { bluetoothState, deviceItem, audioSharingOn ->
                getButtonState(bluetoothState, deviceItem, audioSharingOn)
            }
            .collect { mutableButtonState.value = it }
        awaitCancellation()
    }

    private fun getButtonState(
        bluetoothState: Boolean,
        deviceItem: List<DeviceItem>,
        audioSharingOn: Boolean
    ): AudioSharingButtonState {
        return when {
            // Don't show button when bluetooth is off
            !bluetoothState -> AudioSharingButtonState.Gone
            // Show sharing audio when broadcasting
            audioSharingOn ->
                AudioSharingButtonState.Visible(
                    R.string.quick_settings_bluetooth_audio_sharing_button_sharing,
                    isActive = true
                )
            // When not broadcasting, don't show button if there's connected source in any device
            deviceItem.any {
                BluetoothUtils.hasConnectedBroadcastSource(
                    it.cachedBluetoothDevice,
                    localBluetoothManager
                )
            } -> AudioSharingButtonState.Gone
            // Show audio sharing when there's a connected LE audio device
            deviceItem.any { BluetoothUtils.isActiveLeAudioDevice(it.cachedBluetoothDevice) } ->
                AudioSharingButtonState.Visible(
                    R.string.quick_settings_bluetooth_audio_sharing_button,
                    isActive = false
                )
            else -> AudioSharingButtonState.Gone
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(): AudioSharingButtonViewModel
    }
}
