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
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothVolumeControl
import android.content.ContentResolver
import android.database.ContentObserver
import android.provider.Settings
import androidx.annotation.IntRange
import com.android.internal.util.ConcurrentUtils
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.onBroadcastStartedOrStopped
import com.android.settingslib.bluetooth.onProfileConnectionStateChanged
import com.android.settingslib.bluetooth.onServiceStateChanged
import com.android.settingslib.bluetooth.onSourceConnectedOrRemoved
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MAX
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MIN
import com.android.settingslib.volume.shared.AudioSharingLogger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias GroupIdToVolumes = Map<Int, Int>

/** Provides audio sharing functionality. */
interface AudioSharingRepository {
    /** Whether the device is in audio sharing. */
    val inAudioSharing: StateFlow<Boolean>

    /** The primary headset groupId in audio sharing. */
    val primaryGroupId: StateFlow<Int>

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
    private val contentResolver: ContentResolver,
    private val btManager: LocalBluetoothManager,
    private val coroutineScope: CoroutineScope,
    private val backgroundCoroutineContext: CoroutineContext,
    private val logger: AudioSharingLogger
) : AudioSharingRepository {
    private val isAudioSharingProfilesReady: StateFlow<Boolean> =
        btManager.profileManager.onServiceStateChanged
            .map { isAudioSharingProfilesReady() }
            .onStart { emit(isAudioSharingProfilesReady()) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), false)

    override val inAudioSharing: StateFlow<Boolean> =
        isAudioSharingProfilesReady.flatMapLatest { ready ->
            if (ready) {
                btManager.profileManager.leAudioBroadcastProfile.onBroadcastStartedOrStopped
                    .map { isBroadcasting() }
                    .onStart { emit(isBroadcasting()) }
                    .onEach { logger.onAudioSharingStateChanged(it) }
                    .flowOn(backgroundCoroutineContext)
            } else {
                flowOf(false)
            }
        }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), false)

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
            callback
        )
        awaitClose { contentResolver.unregisterContentObserver(callback) }
    }

    override val primaryGroupId: StateFlow<Int> =
        primaryChange
            .map { BluetoothUtils.getPrimaryGroupIdForBroadcast(contentResolver) }
            .onStart { emit(BluetoothUtils.getPrimaryGroupIdForBroadcast(contentResolver)) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID
            )

    override val secondaryGroupId: StateFlow<Int> =
        merge(
            isAudioSharingProfilesReady.flatMapLatest { ready ->
                if (ready) {
                    btManager.profileManager.leAudioBroadcastAssistantProfile
                        .onSourceConnectedOrRemoved
                        .map { getSecondaryGroupId() }
                } else {
                    emptyFlow()
                }
            },
            btManager.eventManager.onProfileConnectionStateChanged
                .filter { profileConnection ->
                    profileConnection.state == BluetoothAdapter.STATE_DISCONNECTED &&
                            profileConnection.bluetoothProfile ==
                            BluetoothProfile.LE_AUDIO_BROADCAST_ASSISTANT
                }
                .map { getSecondaryGroupId() },
            primaryGroupId.map { getSecondaryGroupId() })
            .onStart { emit(getSecondaryGroupId()) }
            .onEach { logger.onSecondaryGroupIdChanged(it) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID
            )

    override val volumeMap: StateFlow<GroupIdToVolumes> =
        inAudioSharing.flatMapLatest { isSharing ->
            if (isSharing) {
                callbackFlow {
                    val callback =
                        object : BluetoothVolumeControl.Callback {
                            override fun onDeviceVolumeChanged(
                                device: BluetoothDevice,
                                @IntRange(
                                    from = AUDIO_SHARING_VOLUME_MIN.toLong(),
                                    to = AUDIO_SHARING_VOLUME_MAX.toLong()
                                )
                                volume: Int
                            ) {
                                launch { send(Pair(device, volume)) }
                            }
                        }
                    // Once registered, we will receive the initial volume of all
                    // connected BT devices on VolumeControlProfile via callbacks
                    btManager.profileManager.volumeControlProfile.registerCallback(
                        ConcurrentUtils.DIRECT_EXECUTOR, callback
                    )
                    awaitClose {
                        btManager.profileManager.volumeControlProfile.unregisterCallback(
                            callback
                        )
                    }
                }
                    .runningFold(emptyMap<Int, Int>()) { acc, value ->
                        val groupId =
                            BluetoothUtils.getGroupId(
                                btManager.cachedDeviceManager.findDevice(value.first)
                            )
                        if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
                            acc + Pair(groupId, value.second)
                        } else {
                            acc
                        }
                    }
                    .onEach { logger.onVolumeMapChanged(it) }
                    .flowOn(backgroundCoroutineContext)
            } else {
                emptyFlow()
            }
        }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyMap())

    override suspend fun setSecondaryVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        volume: Int
    ) {
        withContext(backgroundCoroutineContext) {
            btManager.profileManager.volumeControlProfile?.let {
                // Find secondary headset and set volume.
                val cachedDevice =
                    BluetoothUtils.getSecondaryDeviceForBroadcast(contentResolver, btManager)
                if (cachedDevice != null) {
                    it.setDeviceVolume(cachedDevice.device, volume, /* isGroupOp= */ true)
                    logger.onSetDeviceVolumeRequested(volume)
                }
            }
        }
    }

    private fun isBroadcastProfileReady(): Boolean =
        btManager.profileManager.leAudioBroadcastProfile?.isProfileReady ?: false

    private fun isAssistantProfileReady(): Boolean =
        btManager.profileManager.leAudioBroadcastAssistantProfile?.isProfileReady ?: false

    private fun isVolumeControlProfileReady(): Boolean =
        btManager.profileManager.volumeControlProfile?.isProfileReady ?: false

    private fun isAudioSharingProfilesReady(): Boolean =
        isBroadcastProfileReady() && isAssistantProfileReady() && isVolumeControlProfileReady()

    private fun isBroadcasting(): Boolean =
        btManager.profileManager.leAudioBroadcastProfile?.isEnabled(null) ?: false

    private fun getSecondaryGroupId(): Int =
        BluetoothUtils.getGroupId(
            BluetoothUtils.getSecondaryDeviceForBroadcast(contentResolver, btManager)
        )
}

class AudioSharingRepositoryEmptyImpl : AudioSharingRepository {
    override val inAudioSharing: StateFlow<Boolean> = MutableStateFlow(false)
    override val primaryGroupId: StateFlow<Int> =
        MutableStateFlow(BluetoothCsipSetCoordinator.GROUP_ID_INVALID)
    override val secondaryGroupId: StateFlow<Int> =
        MutableStateFlow(BluetoothCsipSetCoordinator.GROUP_ID_INVALID)
    override val volumeMap: StateFlow<GroupIdToVolumes> = MutableStateFlow(emptyMap())

    override suspend fun setSecondaryVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        volume: Int
    ) {
    }
}
