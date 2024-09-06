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

package com.android.systemui.volume.domain.interactor

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.media.AudioDeviceInfo
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.media.BluetoothMediaDevice
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.MediaDevice.MediaDeviceType
import com.android.settingslib.media.PhoneMediaDevice
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.settingslib.volume.domain.interactor.AudioModeInteractor
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.component.mediaoutput.domain.interactor.MediaOutputInteractor
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Provides a currently active audio device data. */
@VolumePanelScope
@OptIn(ExperimentalCoroutinesApi::class)
class AudioOutputInteractor
@Inject
constructor(
    @Application private val context: Context,
    audioRepository: AudioRepository,
    audioModeInteractor: AudioModeInteractor,
    @VolumePanelScope scope: CoroutineScope,
    @Background backgroundCoroutineContext: CoroutineContext,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val deviceIconInteractor: DeviceIconInteractor,
    private val mediaOutputInteractor: MediaOutputInteractor,
    audioSharingRepository: AudioSharingRepository,
) {

    val currentAudioDevice: StateFlow<AudioOutputDevice> =
        audioModeInteractor.isOngoingCall
            .flatMapLatest { isOngoingCall ->
                if (isOngoingCall) {
                    audioRepository.communicationDevice.map { communicationDevice ->
                        communicationDevice?.toAudioOutputDevice()
                    }
                } else {
                    mediaOutputInteractor.currentConnectedDevice.map { mediaDevice ->
                        mediaDevice?.toAudioOutputDevice()
                    }
                }
            }
            .map { it ?: AudioOutputDevice.Unknown }
            .flowOn(backgroundCoroutineContext)
            .stateIn(scope, SharingStarted.Eagerly, AudioOutputDevice.Unknown)

    /** Whether the device is in audio sharing */
    val isInAudioSharing: Flow<Boolean> = audioSharingRepository.inAudioSharing

    private fun AudioDeviceInfo.toAudioOutputDevice(): AudioOutputDevice {
        if (
            BluetoothAdapter.checkBluetoothAddress(address) &&
                localBluetoothManager != null &&
                bluetoothAdapter != null
        ) {
            val remoteDevice = bluetoothAdapter.getRemoteDevice(address)
            localBluetoothManager.cachedDeviceManager.findDevice(remoteDevice)?.let {
                device: CachedBluetoothDevice ->
                return AudioOutputDevice.Bluetooth(
                    name = device.name,
                    icon = deviceIconInteractor.loadIcon(device),
                    cachedBluetoothDevice = device,
                )
            }
        }
        // Built-in device has an empty address
        if (address.isNotEmpty()) {
            return AudioOutputDevice.Wired(
                name = productName.toString(),
                icon = deviceIconInteractor.loadIcon(type),
            )
        }
        return AudioOutputDevice.BuiltIn(
            name = PhoneMediaDevice.getMediaTransferThisDeviceName(context),
            icon = deviceIconInteractor.loadIcon(type),
        )
    }

    private fun MediaDevice.toAudioOutputDevice(): AudioOutputDevice {
        return when {
            this is BluetoothMediaDevice ->
                AudioOutputDevice.Bluetooth(
                    name = name,
                    icon = icon,
                    cachedBluetoothDevice = cachedDevice,
                )
            deviceType == MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE ||
                deviceType == MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE ->
                AudioOutputDevice.Wired(
                    name = name,
                    icon = icon,
                )
            else ->
                AudioOutputDevice.BuiltIn(
                    name = name,
                    icon = icon,
                )
        }
    }
}
