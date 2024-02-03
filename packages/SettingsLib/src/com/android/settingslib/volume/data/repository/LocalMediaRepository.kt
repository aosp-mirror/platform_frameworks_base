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
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/** Repository providing data about connected media devices. */
interface LocalMediaRepository {

    /** Available devices list */
    val mediaDevices: StateFlow<Collection<MediaDevice>>

    /** Currently connected media device */
    val currentConnectedDevice: StateFlow<MediaDevice?>
}

class LocalMediaRepositoryImpl(
    private val localMediaManager: LocalMediaManager,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : LocalMediaRepository {

    private val deviceUpdates: Flow<DevicesUpdate> = callbackFlow {
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
        localMediaManager.startScan()

        awaitClose {
            localMediaManager.stopScan()
            localMediaManager.unregisterCallback(callback)
        }
    }

    override val mediaDevices: StateFlow<Collection<MediaDevice>> =
        deviceUpdates
            .mapNotNull {
                if (it is DevicesUpdate.DeviceListUpdate) {
                    it.newDevices ?: emptyList()
                } else {
                    null
                }
            }
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyList())

    override val currentConnectedDevice: StateFlow<MediaDevice?> =
        deviceUpdates
            .map { localMediaManager.currentConnectedDevice }
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                localMediaManager.currentConnectedDevice
            )

    private sealed interface DevicesUpdate {

        data class DeviceListUpdate(val newDevices: List<MediaDevice>?) : DevicesUpdate

        data object SelectedDeviceStateChanged : DevicesUpdate

        data object DeviceAttributesChanged : DevicesUpdate
    }
}
