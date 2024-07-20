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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothCsipSetCoordinator
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeBroadcast
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothVolumeControl
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.provider.Settings
import androidx.annotation.IntRange
import com.android.internal.util.ConcurrentUtils
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.onProfileConnectionStateChanged
import com.android.settingslib.bluetooth.onSourceConnectedOrRemoved
import com.android.settingslib.flags.Flags
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MAX
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MIN
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias GroupIdToVolumes = Map<Int, Int>

/** Provides audio sharing functionality. */
interface AudioSharingRepository {
    /** Whether the device is in audio sharing. */
    val inAudioSharing: Flow<Boolean>

    /** The secondary headset groupId in audio sharing. */
    val secondaryGroupId: StateFlow<Int>

    /** The headset groupId to volume map during audio sharing. */
    val volumeMap: StateFlow<GroupIdToVolumes>

    /** Set the volume of secondary headset during audio sharing. */
    suspend fun setSecondaryVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        volume: Int
    )

    companion object {
        const val AUDIO_SHARING_VOLUME_MIN = 0
        const val AUDIO_SHARING_VOLUME_MAX = 255
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingRepositoryImpl(
    private val context: Context,
    private val contentResolver: ContentResolver,
    private val btManager: LocalBluetoothManager?,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
) : AudioSharingRepository {
    override val inAudioSharing: Flow<Boolean> =
        if (Flags.enableLeAudioSharing()) {
            btManager?.profileManager?.leAudioBroadcastProfile?.let { leBroadcast ->
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

    private val primaryChange: Flow<Unit> = callbackFlow {
        val callback =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    launch { send(Unit) }
                }
            }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(BluetoothUtils.getPrimaryGroupIdUriForBroadcast()),
            false,
            callback)
        awaitClose { contentResolver.unregisterContentObserver(callback) }
    }

    override val secondaryGroupId: StateFlow<Int> =
        if (Flags.volumeDialogAudioSharingFix()) {
                merge(
                        btManager
                            ?.profileManager
                            ?.leAudioBroadcastAssistantProfile
                            ?.onSourceConnectedOrRemoved
                            ?.map { getSecondaryGroupId() } ?: emptyFlow(),
                        btManager
                            ?.eventManager
                            ?.onProfileConnectionStateChanged
                            ?.filter { profileConnection ->
                                profileConnection.state == BluetoothAdapter.STATE_DISCONNECTED &&
                                    profileConnection.bluetoothProfile ==
                                        BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT
                            }
                            ?.map { getSecondaryGroupId() } ?: emptyFlow(),
                        primaryChange.map { getSecondaryGroupId() })
                    .onStart { emit(getSecondaryGroupId()) }
                    .distinctUntilChanged()
                    .flowOn(backgroundCoroutineContext)
            } else {
                emptyFlow()
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), getSecondaryGroupId())

    override val volumeMap: StateFlow<GroupIdToVolumes> =
        if (Flags.volumeDialogAudioSharingFix()) {
            btManager?.profileManager?.volumeControlProfile?.let { volumeControl ->
                inAudioSharing.flatMapLatest { isSharing ->
                    if (isSharing) {
                        callbackFlow {
                                val callback =
                                    object : BluetoothVolumeControl.Callback {
                                        override fun onDeviceVolumeChanged(
                                            device: BluetoothDevice,
                                            @IntRange(
                                                from = AUDIO_SHARING_VOLUME_MIN.toLong(),
                                                to = AUDIO_SHARING_VOLUME_MAX.toLong())
                                            volume: Int
                                        ) {
                                            launch { send(Pair(device, volume)) }
                                        }
                                    }
                                // Once registered, we will receive the initial volume of all
                                // connected BT devices on VolumeControlProfile via callbacks
                                volumeControl.registerCallback(
                                    ConcurrentUtils.DIRECT_EXECUTOR, callback)
                                awaitClose { volumeControl.unregisterCallback(callback) }
                            }
                            .runningFold(emptyMap<Int, Int>()) { acc, value ->
                                val groupId =
                                    BluetoothUtils.getGroupId(
                                        btManager.cachedDeviceManager?.findDevice(value.first))
                                if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                                    acc + Pair(groupId, value.second)
                                } else {
                                    acc
                                }
                            }
                            .distinctUntilChanged()
                            .flowOn(backgroundCoroutineContext)
                    } else {
                        emptyFlow()
                    }
                }
            } ?: emptyFlow()
        } else {
            emptyFlow()
        }
        .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyMap())

    override suspend fun setSecondaryVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        volume: Int
    ) {
        withContext(backgroundCoroutineContext) {
            if (Flags.volumeDialogAudioSharingFix()) {
                btManager?.profileManager?.volumeControlProfile?.let {
                    // Find secondary headset and set volume.
                    val cachedDevice =
                        BluetoothUtils.getSecondaryDeviceForBroadcast(context, btManager)
                    if (cachedDevice != null) {
                        it.setDeviceVolume(cachedDevice.device, volume, /* isGroupOp= */ true)
                    }
                }
            }
        }
    }

    private fun isBroadcasting(): Boolean {
        return Flags.enableLeAudioSharing() &&
            (btManager?.profileManager?.leAudioBroadcastProfile?.isEnabled(null) ?: false)
    }

    private fun getSecondaryGroupId(): Int {
        return BluetoothUtils.getGroupId(
            BluetoothUtils.getSecondaryDeviceForBroadcast(context, btManager))
    }
}
