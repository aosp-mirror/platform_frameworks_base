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

import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import com.android.internal.util.ConcurrentUtils
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.flags.Flags
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/** Provides audio sharing functionality. */
interface AudioSharingRepository {
    /** Whether the device is in audio sharing. */
    val inAudioSharing: Flow<Boolean>
}

class AudioSharingRepositoryImpl(
    private val localBluetoothManager: LocalBluetoothManager?,
    backgroundCoroutineContext: CoroutineContext,
) : AudioSharingRepository {
    override val inAudioSharing: Flow<Boolean> =
        if (Flags.enableLeAudioSharing()) {
            localBluetoothManager?.profileManager?.leAudioBroadcastProfile?.let { leBroadcast ->
                callbackFlow {
                        val listener =
                            object : BluetoothLeBroadcast.Callback {
                                override fun onBroadcastStarted(reason: Int, broadcastId: Int) {
                                    launch { send(isBroadcasting()) }
                                }

                                override fun onBroadcastStartFailed(reason: Int) {
                                    launch { send(isBroadcasting()) }
                                }

                                override fun onBroadcastStopped(reason: Int, broadcastId: Int) {
                                    launch { send(isBroadcasting()) }
                                }

                                override fun onBroadcastStopFailed(reason: Int) {
                                    launch { send(isBroadcasting()) }
                                }

                                override fun onPlaybackStarted(reason: Int, broadcastId: Int) {}

                                override fun onPlaybackStopped(reason: Int, broadcastId: Int) {}

                                override fun onBroadcastUpdated(reason: Int, broadcastId: Int) {}

                                override fun onBroadcastUpdateFailed(
                                    reason: Int,
                                    broadcastId: Int
                                ) {}

                                override fun onBroadcastMetadataChanged(
                                    broadcastId: Int,
                                    metadata: BluetoothLeBroadcastMetadata
                                ) {}
                            }

                        leBroadcast.registerServiceCallBack(
                            ConcurrentUtils.DIRECT_EXECUTOR,
                            listener,
                        )
                        awaitClose { leBroadcast.unregisterServiceCallBack(listener) }
                    }
                    .onStart { emit(isBroadcasting()) }
                    .flowOn(backgroundCoroutineContext)
            } ?: flowOf(false)
        } else {
            flowOf(false)
        }

    private fun isBroadcasting(): Boolean {
        return Flags.enableLeAudioSharing() &&
            (localBluetoothManager?.profileManager?.leAudioBroadcastProfile?.isEnabled(null)
                ?: false)
    }
}
