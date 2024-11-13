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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcastAssistant
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothLeBroadcastReceiveState
import com.android.internal.util.ConcurrentUtils
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** [Flow] for [BluetoothLeBroadcastAssistant.Callback] source connected/removed events */
val LocalBluetoothLeBroadcastAssistant.onSourceConnectedOrRemoved: Flow<Unit>
    get() = callbackFlow {
        val callback =
            object : BluetoothLeBroadcastAssistant.Callback {
                override fun onReceiveStateChanged(
                    sink: BluetoothDevice,
                    sourceId: Int,
                    state: BluetoothLeBroadcastReceiveState
                ) {
                    if (BluetoothUtils.isConnected(state)) {
                        launch { send(Unit) }
                    }
                }

                override fun onSourceRemoved(sink: BluetoothDevice, sourceId: Int, reason: Int) {
                    launch { send(Unit) }
                }

                override fun onSearchStarted(reason: Int) {}

                override fun onSearchStartFailed(reason: Int) {}

                override fun onSearchStopped(reason: Int) {}

                override fun onSearchStopFailed(reason: Int) {}

                override fun onSourceFound(source: BluetoothLeBroadcastMetadata) {}

                override fun onSourceAdded(sink: BluetoothDevice, sourceId: Int, reason: Int) {}

                override fun onSourceAddFailed(
                    sink: BluetoothDevice,
                    source: BluetoothLeBroadcastMetadata,
                    reason: Int
                ) {}

                override fun onSourceModified(sink: BluetoothDevice, sourceId: Int, reason: Int) {}

                override fun onSourceModifyFailed(
                    sink: BluetoothDevice,
                    sourceId: Int,
                    reason: Int
                ) {}

                override fun onSourceRemoveFailed(
                    sink: BluetoothDevice,
                    sourceId: Int,
                    reason: Int
                ) {}
            }
        registerServiceCallBack(
            ConcurrentUtils.DIRECT_EXECUTOR,
            callback,
        )
        awaitClose {
            if (BluetoothAdapter.getDefaultAdapter()?.isEnabled == true) {
                unregisterServiceCallBack(callback)
            }
        }
    }
