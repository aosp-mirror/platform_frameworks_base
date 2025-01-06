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

import android.content.Context
import androidx.annotation.WorkerThread
import com.android.settingslib.bluetooth.BluetoothUtils
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.onBroadcastMetadataChanged
import com.android.settingslib.flags.Flags.audioSharingQsDialogImprovement
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
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

    suspend fun audioSharingAvailable(): Boolean

    suspend fun qsDialogImprovementAvailable(): Boolean
}

@SysUISingleton
@OptIn(ExperimentalCoroutinesApi::class)
class AudioSharingInteractorImpl
@Inject
constructor(
    private val context: Context,
    private val localBluetoothManager: LocalBluetoothManager?,
    private val audioSharingRepository: AudioSharingRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : AudioSharingInteractor {

    private var previewEnabled: Boolean? = null

    override val isAudioSharingOn: Flow<Boolean> =
        flow { emit(audioSharingAvailable()) }
            .flatMapLatest { isEnabled ->
                if (isEnabled) {
                    audioSharingRepository.inAudioSharing
                } else {
                    flowOf(false)
                }
            }
            .flowOn(backgroundDispatcher)

    override val audioSourceStateUpdate =
        isAudioSharingOn
            .flatMapLatest {
                if (it) {
                    audioSharingRepository.audioSourceStateUpdate
                } else {
                    emptyFlow()
                }
            }
            .flowOn(backgroundDispatcher)

    override suspend fun handleAudioSourceWhenReady() {
        withContext(backgroundDispatcher) {
            if (audioSharingAvailable()) {
                audioSharingRepository.leAudioBroadcastProfile?.let { profile ->
                    isAudioSharingOn
                        .mapNotNull { audioSharingOn ->
                            if (audioSharingOn) {
                                // onBroadcastMetadataChanged could emit multiple times during one
                                // audio sharing session, we only perform add source on the first
                                // time
                                profile.onBroadcastMetadataChanged.firstOrNull()
                            } else {
                                null
                            }
                        }
                        .flowOn(backgroundDispatcher)
                        .collect { audioSharingRepository.addSource() }
                }
            }
        }
    }

    override suspend fun isAvailableAudioSharingMediaBluetoothDevice(
        cachedBluetoothDevice: CachedBluetoothDevice
    ): Boolean {
        return withContext(backgroundDispatcher) {
            if (audioSharingAvailable()) {
                BluetoothUtils.isAvailableAudioSharingMediaBluetoothDevice(
                    cachedBluetoothDevice,
                    localBluetoothManager,
                )
            } else {
                false
            }
        }
    }

    override suspend fun switchActive(cachedBluetoothDevice: CachedBluetoothDevice) {
        if (!audioSharingAvailable()) {
            return
        }
        audioSharingRepository.setActive(cachedBluetoothDevice)
    }

    override suspend fun startAudioSharing() {
        if (!audioSharingAvailable()) {
            return
        }
        audioSharingRepository.startAudioSharing()
    }

    // TODO(b/367965193): Move this after flags rollout
    override suspend fun audioSharingAvailable(): Boolean {
        return audioSharingRepository.audioSharingAvailable()
    }

    override suspend fun qsDialogImprovementAvailable(): Boolean {
        return withContext(backgroundDispatcher) {
            audioSharingQsDialogImprovement() || isAudioSharingPreviewEnabled()
        }
    }

    @WorkerThread
    private fun isAudioSharingPreviewEnabled(): Boolean {
        if (previewEnabled == null) {
            previewEnabled = BluetoothUtils.isAudioSharingPreviewEnabled(context.contentResolver)
        }
        return previewEnabled ?: false
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

    override suspend fun audioSharingAvailable(): Boolean = false

    override suspend fun qsDialogImprovementAvailable(): Boolean = false
}
