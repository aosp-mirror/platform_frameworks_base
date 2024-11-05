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

import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcastAssistant
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.onSourceConnectedOrRemoved
import com.android.settingslib.volume.data.repository.AudioSharingRepository as SettingsLibAudioSharingRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

interface AudioSharingRepository {
    val leAudioBroadcastProfile: LocalBluetoothLeBroadcast?

    val audioSourceStateUpdate: Flow<Unit>

    val inAudioSharing: StateFlow<Boolean>

    suspend fun audioSharingAvailable(): Boolean

    suspend fun addSource()

    suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice)

    suspend fun startAudioSharing()
}

@SysUISingleton
class AudioSharingRepositoryImpl(
    private val localBluetoothManager: LocalBluetoothManager,
    private val settingsLibAudioSharingRepository: SettingsLibAudioSharingRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AudioSharingRepository {

    override val leAudioBroadcastProfile: LocalBluetoothLeBroadcast?
        get() = localBluetoothManager.profileManager?.leAudioBroadcastProfile

    private val leAudioBroadcastAssistantProfile: LocalBluetoothLeBroadcastAssistant?
        get() = localBluetoothManager.profileManager?.leAudioBroadcastAssistantProfile

    override val audioSourceStateUpdate: Flow<Unit> =
        leAudioBroadcastAssistantProfile?.onSourceConnectedOrRemoved ?: emptyFlow()

    override val inAudioSharing: StateFlow<Boolean> =
        settingsLibAudioSharingRepository.inAudioSharing

    override suspend fun audioSharingAvailable(): Boolean {
        return settingsLibAudioSharingRepository.audioSharingAvailable()
    }

    override suspend fun addSource() {
        withContext(backgroundDispatcher) {
            if (!settingsLibAudioSharingRepository.audioSharingAvailable()) {
                return@withContext
            }
            leAudioBroadcastProfile?.latestBluetoothLeBroadcastMetadata?.let { metadata ->
                leAudioBroadcastAssistantProfile?.let {
                    it.allConnectedDevices.forEach { sink -> it.addSource(sink, metadata, false) }
                }
            }
        }
    }

    override suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice) {
        withContext(backgroundDispatcher) {
            if (!settingsLibAudioSharingRepository.audioSharingAvailable()) {
                return@withContext
            }
            cachedBluetoothDevice.setActive()
        }
    }

    override suspend fun startAudioSharing() {
        withContext(backgroundDispatcher) {
            if (!settingsLibAudioSharingRepository.audioSharingAvailable()) {
                return@withContext
            }
            leAudioBroadcastProfile?.startPrivateBroadcast()
        }
    }
}

@SysUISingleton
class AudioSharingRepositoryEmptyImpl : AudioSharingRepository {
    override val leAudioBroadcastProfile: LocalBluetoothLeBroadcast? = null

    override val audioSourceStateUpdate: Flow<Unit> = emptyFlow()

    override val inAudioSharing: StateFlow<Boolean> = MutableStateFlow(false)

    override suspend fun audioSharingAvailable(): Boolean = false

    override suspend fun addSource() {}

    override suspend fun setActive(cachedBluetoothDevice: CachedBluetoothDevice) {}

    override suspend fun startAudioSharing() {}
}
