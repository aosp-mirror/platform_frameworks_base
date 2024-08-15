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

package com.android.settingslib.bluetooth

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** [Flow] for [BluetoothCallback] device profile connection state change events */
val BluetoothEventManager.onProfileConnectionStateChanged: Flow<ProfileConnectionState>
    get() = callbackFlow {
        val callback =
            object : BluetoothCallback {
                override fun onProfileConnectionStateChanged(
                    cachedDevice: CachedBluetoothDevice,
                    @BluetoothCallback.ConnectionState state: Int,
                    bluetoothProfile: Int
                ) {
                    launch { send(ProfileConnectionState(cachedDevice, state, bluetoothProfile)) }
                }
            }
        registerCallback(callback)
        awaitClose { unregisterCallback(callback) }
    }
