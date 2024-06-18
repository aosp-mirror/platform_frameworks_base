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
package com.android.settingslib.volume.data.repository

import com.android.settingslib.media.LocalMediaManager
import com.android.settingslib.media.MediaDevice
import com.android.settingslib.media.flags.Flags
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/** Repository providing data about connected media devices. */
interface LocalMediaRepository {

    /** Currently connected media device */
    val currentConnectedDevice: StateFlow<MediaDevice?>
}

class LocalMediaRepositoryImpl(
    audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val localMediaManager: LocalMediaManager,
    coroutineScope: CoroutineScope,
) : LocalMediaRepository {

    private val devicesChanges =
        audioManagerEventsReceiver.events.filterIsInstance(
            AudioManagerEvent.StreamDevicesChanged::class
        )
    private val mediaDevicesUpdates: Flow<DevicesUpdate> =
        callbackFlow {
                val callback =
                    object : LocalMediaManager.DeviceCallback {
                        override fun onDeviceListUpdate(newDevices: List<MediaDevice>?) {
                            trySend(DevicesUpdate.DeviceListUpdate(newDevices ?: emptyList()))
                        }

                        override fun onSelectedDeviceStateChanged(
                            device: MediaDevice?,
                            state: Int,
                        ) {
                            trySend(DevicesUpdate.SelectedDeviceStateChanged)
                        }

                        override fun onDeviceAttributesChanged() {
                            trySend(DevicesUpdate.DeviceAttributesChanged)
                        }
                    }
                localMediaManager.registerCallback(callback)
                if (!Flags.removeUnnecessaryRouteScanning()) {
                    localMediaManager.startScan()
                }

                awaitClose {
                    if (!Flags.removeUnnecessaryRouteScanning()) {
                        localMediaManager.stopScan()
                    }
                    localMediaManager.unregisterCallback(callback)
                }
            }
            .shareIn(coroutineScope, SharingStarted.Eagerly, replay = 0)

    override val currentConnectedDevice: StateFlow<MediaDevice?> =
        merge(devicesChanges, mediaDevicesUpdates)
            .map { localMediaManager.currentConnectedDevice }
            .onStart { emit(localMediaManager.currentConnectedDevice) }
            .stateIn(
                coroutineScope,
                SharingStarted.Eagerly,
                localMediaManager.currentConnectedDevice,
            )

    private sealed interface DevicesUpdate {

        data class DeviceListUpdate(val newDevices: List<MediaDevice>?) : DevicesUpdate

        data object SelectedDeviceStateChanged : DevicesUpdate

        data object DeviceAttributesChanged : DevicesUpdate
    }
}
