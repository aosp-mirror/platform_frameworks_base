/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.onPlaybackStarted
import com.android.settingslib.volume.data.repository.AudioSharingRepository as SettingsLibAudioSharingRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

/** Holds business logic for the audio sharing state. */
interface AudioSharingInteractor {
    val isAudioSharingOn: Flow<Boolean>

    val audioSourceStateUpdate: Flow<Unit>

    suspend fun handleAudioSourceWhenReady()

    suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): Boolean

    suspend fun switchActive(cachedBluetoothDevice: CachedBluetoothDevice)

    suspend fun startAudioSharing()
}

@SysUISingleton
class AudioSharingInteractorImpl
@Inject
constructor(
    private val localBluetoothManager: LocalBluetoothManager?,
    private val audioSharingRepository: AudioSharingRepository,
    settingsLibAudioSharingRepository: SettingsLibAudioSharingRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AudioSharingInteractor {

    override val isAudioSharingOn = settingsLibAudioSharingRepository.inAudioSharing

    override val audioSourceStateUpdate = audioSharingRepository.audioSourceStateUpdate

    override suspend fun handleAudioSourceWhenReady() {
        withContext(backgroundDispatcher) {
            audioSharingRepository.leAudioBroadcastProfile?.let { profile ->
                isAudioSharingOn
                    .mapNotNull { audioSharingOn ->
                        if (audioSharingOn) {
                            // onPlaybackStarted could emit multiple times during one audio sharing
                            // session, we only perform add source on the first time
                            profile.onPlaybackStarted.firstOrNull()
                        } else {
                            null
                        }
                    }
                    .collect { audioSharingRepository.addSource() }
            }
        }
    }

    override suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): Boolean {
        return withContext(backgroundDispatcher) {
            BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice(
                cachedBluetoothDevice,
                localBluetoothManager
            )
        }
    }

    override suspend fun switchActive(cachedBluetoothDevice: CachedBluetoothDevice) {
        audioSharingRepository.setActive(cachedBluetoothDevice)
    }

    override suspend fun startAudioSharing() {
        audioSharingRepository.startAudioSharing()
    }
}

@SysUISingleton
class AudioSharingInteractorEmptyImpl @Inject constructor() : AudioSharingInteractor {
    override val isAudioSharingOn: Flow<Boolean> = flowOf(false)

    override val audioSourceStateUpdate: Flow<Unit> = emptyFlow()

    override suspend fun handleAudioSourceWhenReady() {}

    override suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ) = false

    override suspend fun switchActive(cachedBluetoothDevice: CachedBluetoothDevice) {}

    override suspend fun startAudioSharing() {}
}
