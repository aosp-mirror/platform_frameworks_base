/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.media.domain.interactor

import android.media.AudioDeviceAttributes
import com.android.settingslib.media.data.repository.SpatializerRepository

class FakeSpatializerRepository : SpatializerRepository {

    private val availabilityByDevice: MutableMap<AudioDeviceAttributes, Boolean> = mutableMapOf()
    private val compatibleDevices: MutableList<AudioDeviceAttributes> = mutableListOf()

    override suspend fun isAvailableForDevice(
        audioDeviceAttributes: AudioDeviceAttributes
    ): Boolean = availabilityByDevice.getOrDefault(audioDeviceAttributes, false)

    override suspend fun getCompatibleDevices(): Collection<AudioDeviceAttributes> =
        compatibleDevices

    override suspend fun addCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes) {
        compatibleDevices.add(audioDeviceAttributes)
    }

    override suspend fun removeCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes) {
        compatibleDevices.remove(audioDeviceAttributes)
    }

    fun setIsAvailable(audioDeviceAttributes: AudioDeviceAttributes, isAvailable: Boolean) {
        availabilityByDevice[audioDeviceAttributes] = isAvailable
    }
}
