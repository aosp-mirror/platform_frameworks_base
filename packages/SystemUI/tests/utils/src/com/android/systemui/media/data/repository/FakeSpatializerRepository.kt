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

package com.android.systemui.media.data.repository

import android.media.AudioDeviceAttributes
import com.android.settingslib.media.data.repository.SpatializerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSpatializerRepository : SpatializerRepository {

    var defaultSpatialAudioAvailable: Boolean = false

    private val spatialAudioAvailabilityByDevice: MutableMap<AudioDeviceAttributes, Boolean> =
        mutableMapOf()
    private val spatialAudioCompatibleDevices: MutableList<AudioDeviceAttributes> = mutableListOf()

    private val mutableHeadTrackingAvailable = MutableStateFlow(false)
    private val headTrackingEnabledByDevice = mutableMapOf<AudioDeviceAttributes, Boolean>()

    override val isHeadTrackingAvailable: StateFlow<Boolean> =
        mutableHeadTrackingAvailable.asStateFlow()

    override suspend fun isSpatialAudioAvailableForDevice(
        audioDeviceAttributes: AudioDeviceAttributes
    ): Boolean =
        spatialAudioAvailabilityByDevice.getOrDefault(
            audioDeviceAttributes,
            defaultSpatialAudioAvailable
        )

    override suspend fun getSpatialAudioCompatibleDevices(): Collection<AudioDeviceAttributes> =
        spatialAudioCompatibleDevices

    override suspend fun addSpatialAudioCompatibleDevice(
        audioDeviceAttributes: AudioDeviceAttributes
    ) {
        spatialAudioCompatibleDevices.add(audioDeviceAttributes)
    }

    override suspend fun removeSpatialAudioCompatibleDevice(
        audioDeviceAttributes: AudioDeviceAttributes
    ) {
        spatialAudioCompatibleDevices.remove(audioDeviceAttributes)
    }

    override suspend fun isHeadTrackingEnabled(
        audioDeviceAttributes: AudioDeviceAttributes
    ): Boolean = headTrackingEnabledByDevice.getOrDefault(audioDeviceAttributes, false)

    override suspend fun setHeadTrackingEnabled(
        audioDeviceAttributes: AudioDeviceAttributes,
        isEnabled: Boolean
    ) {
        headTrackingEnabledByDevice[audioDeviceAttributes] = isEnabled
    }

    fun setIsSpatialAudioAvailable(
        audioDeviceAttributes: AudioDeviceAttributes,
        isAvailable: Boolean,
    ) {
        spatialAudioAvailabilityByDevice[audioDeviceAttributes] = isAvailable
    }

    fun setIsHeadTrackingAvailable(isAvailable: Boolean) {
        mutableHeadTrackingAvailable.value = isAvailable
    }
}
