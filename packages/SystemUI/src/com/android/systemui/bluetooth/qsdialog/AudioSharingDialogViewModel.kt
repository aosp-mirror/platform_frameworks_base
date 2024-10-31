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

import android.content.Context
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.res.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import com.android.app.tracing.coroutines.launchTraced as launch

sealed class AudioSharingDialogState {
    data object Hide : AudioSharingDialogState()

    data class Show(val subtitle: String, val switchButtonText: String) : AudioSharingDialogState()
}

class AudioSharingDialogViewModel
@AssistedInject
constructor(
    deviceItemInteractor: DeviceItemInteractor,
    private val audioSharingInteractor: AudioSharingInteractor,
    private val context: Context,
    private val localBluetoothManager: LocalBluetoothManager?,
    @Assisted private val cachedBluetoothDevice: CachedBluetoothDevice,
    @Assisted private val coroutineScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    val dialogState: Flow<AudioSharingDialogState> =
        deviceItemInteractor.deviceItemUpdateRequest
            .map {
                if (
                    audioSharingInteractor.isAvailableAudioSharingMediaBluetoothDevice(
                        cachedBluetoothDevice
                    )
                ) {
                    createShowState(cachedBluetoothDevice)
                } else {
                    AudioSharingDialogState.Hide
                }
            }
            .onStart { emit(createShowState(cachedBluetoothDevice)) }
            .flowOn(backgroundDispatcher)
            .distinctUntilChanged()

    fun switchActiveClicked() {
        coroutineScope.launch { audioSharingInteractor.switchActive(cachedBluetoothDevice) }
    }

    fun shareAudioClicked() {
        coroutineScope.launch { audioSharingInteractor.startAudioSharing() }
    }

    private fun createShowState(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): AudioSharingDialogState {
        val activeDeviceName =
            localBluetoothManager
                ?.profileManager
                ?.leAudioProfile
                ?.activeDevices
                ?.firstOrNull()
                ?.let { localBluetoothManager.cachedDeviceManager?.findDevice(it)?.name } ?: ""
        val availableDeviceName = cachedBluetoothDevice.name
        return AudioSharingDialogState.Show(
            context.getString(
                R.string.quick_settings_bluetooth_audio_sharing_dialog_subtitle,
                availableDeviceName,
                activeDeviceName
            ),
            context.getString(
                R.string.quick_settings_bluetooth_audio_sharing_dialog_switch_to_button,
                availableDeviceName
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(
            cachedBluetoothDevice: CachedBluetoothDevice,
            coroutineScope: CoroutineScope
        ): AudioSharingDialogViewModel
    }
}
