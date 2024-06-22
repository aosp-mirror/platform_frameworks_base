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

package com.android.systemui.volume.data.repository

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.settingslib.volume.shared.model.RingerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FakeAudioRepository : AudioRepository {

    private val mutableMode = MutableStateFlow(AudioManager.MODE_NORMAL)
    override val mode: StateFlow<Int> = mutableMode.asStateFlow()

    private val mutableRingerMode = MutableStateFlow(RingerMode(AudioManager.RINGER_MODE_NORMAL))
    override val ringerMode: StateFlow<RingerMode> = mutableRingerMode.asStateFlow()

    private val mutableCommunicationDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    override val communicationDevice: StateFlow<AudioDeviceInfo?> =
        mutableCommunicationDevice.asStateFlow()

    private val models: MutableMap<AudioStream, MutableStateFlow<AudioStreamModel>> = mutableMapOf()
    private val lastAudibleVolumes: MutableMap<AudioStream, Int> = mutableMapOf()
    private val deviceCategories: MutableMap<String, Int> = mutableMapOf()

    private fun getAudioStreamModelState(
        audioStream: AudioStream
    ): MutableStateFlow<AudioStreamModel> =
        models.getOrPut(audioStream) {
            MutableStateFlow(
                AudioStreamModel(
                    audioStream = audioStream,
                    volume = 0,
                    minVolume = 0,
                    maxVolume = 10,
                    isAffectedByMute = false,
                    isAffectedByRingerMode = false,
                    isMuted = false,
                )
            )
        }

    override fun getAudioStream(audioStream: AudioStream): Flow<AudioStreamModel> =
        getAudioStreamModelState(audioStream).asStateFlow()

    override suspend fun setVolume(audioStream: AudioStream, volume: Int) {
        getAudioStreamModelState(audioStream).update { it.copy(volume = volume) }
    }

    override suspend fun setMuted(audioStream: AudioStream, isMuted: Boolean): Boolean {
        val modelState = getAudioStreamModelState(audioStream)
        return if (modelState.value.isMuted == isMuted) {
            false
        } else {
            modelState.update { it.copy(isMuted = isMuted) }
            true
        }
    }

    override suspend fun getLastAudibleVolume(audioStream: AudioStream): Int =
        lastAudibleVolumes.getOrDefault(audioStream, 0)

    fun setMode(newMode: Int) {
        mutableMode.value = newMode
    }

    fun setRingerMode(newRingerMode: RingerMode) {
        mutableRingerMode.value = newRingerMode
    }

    fun setCommunicationDevice(device: AudioDeviceInfo?) {
        mutableCommunicationDevice.value = device
    }

    fun setAudioStreamModel(model: AudioStreamModel) {
        getAudioStreamModelState(model.audioStream).update { model }
    }

    fun setLastAudibleVolume(audioStream: AudioStream, volume: Int) {
        lastAudibleVolumes[audioStream] = volume
    }

    override suspend fun setRingerMode(audioStream: AudioStream, mode: RingerMode) {
        mutableRingerMode.value = mode
    }

    fun setBluetoothAudioDeviceCategory(bluetoothAddress: String, category: Int) {
        deviceCategories[bluetoothAddress] = category
    }

    override suspend fun getBluetoothAudioDeviceCategory(bluetoothAddress: String): Int {
        return deviceCategories[bluetoothAddress] ?: AudioManager.AUDIO_DEVICE_CATEGORY_UNKNOWN
    }
}
