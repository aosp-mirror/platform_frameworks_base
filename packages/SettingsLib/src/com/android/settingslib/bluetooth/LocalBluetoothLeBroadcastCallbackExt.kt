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

import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import com.android.internal.util.ConcurrentUtils
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** [Flow] for [BluetoothLeBroadcast.Callback] source start/stop events */
val LocalBluetoothLeBroadcast.onBroadcastStartedOrStopped: Flow<Unit>
    get() =
        callbackFlow {
                val listener =
                    object : BluetoothLeBroadcast.Callback {
                        override fun onBroadcastStarted(reason: Int, broadcastId: Int) {
                            launch { trySend(Unit) }
                        }

                        override fun onBroadcastStartFailed(reason: Int) {
                            launch { trySend(Unit) }
                        }

                        override fun onBroadcastStopped(reason: Int, broadcastId: Int) {
                            launch { trySend(Unit) }
                        }

                        override fun onBroadcastStopFailed(reason: Int) {
                            launch { trySend(Unit) }
                        }

                        override fun onPlaybackStarted(reason: Int, broadcastId: Int) {}

                        override fun onPlaybackStopped(reason: Int, broadcastId: Int) {}

                        override fun onBroadcastUpdated(reason: Int, broadcastId: Int) {}

                        override fun onBroadcastUpdateFailed(reason: Int, broadcastId: Int) {}

                        override fun onBroadcastMetadataChanged(
                            broadcastId: Int,
                            metadata: BluetoothLeBroadcastMetadata
                        ) {}
                    }
                registerServiceCallBack(
                    ConcurrentUtils.DIRECT_EXECUTOR,
                    listener,
                )
                awaitClose { unregisterServiceCallBack(listener) }
            }
            .buffer(capacity = Channel.CONFLATED)
