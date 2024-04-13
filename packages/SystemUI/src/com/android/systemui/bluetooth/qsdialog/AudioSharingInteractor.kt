/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

internal sealed class AudioSharingButtonState {
    object Gone : AudioSharingButtonState()
    data class Visible(@StringRes val resId: Int) : AudioSharingButtonState()
}

/** Holds business logic for the audio sharing state. */
@SysUISingleton
internal class AudioSharingInteractor
@Inject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    bluetoothStateInteractor: BluetoothStateInteractor,
    deviceItemInteractor: DeviceItemInteractor,
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    /** Flow representing the update of AudioSharingButtonState. */
    internal val audioSharingButtonStateUpdate: Flow<AudioSharingButtonState> =
        combine(
                bluetoothStateInteractor.bluetoothStateUpdate,
                deviceItemInteractor.deviceItemUpdate
            ) { bluetoothState, deviceItem ->
                getButtonState(bluetoothState, deviceItem)
            }
            .flowOn(backgroundDispatcher)
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(replayExpirationMillis = 0),
                initialValue = AudioSharingButtonState.Gone
            )

    private fun getButtonState(
        bluetoothState: Boolean,
        deviceItem: List<DeviceItem>
    ): AudioSharingButtonState {
        return when {
            // Don't show button when bluetooth is off
            !bluetoothState -> AudioSharingButtonState.Gone
            // Show sharing audio when broadcasting
            BluetoothUtils.isBroadcasting(localBluetoothManager) ->
                AudioSharingButtonState.Visible(
                    R.string.quick_settings_bluetooth_audio_sharing_button_sharing
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
                    R.string.quick_settings_bluetooth_audio_sharing_button
                )
            else -> AudioSharingButtonState.Gone
        }
    }
}
