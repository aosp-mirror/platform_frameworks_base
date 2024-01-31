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

package com.android.settingslib.media.data.repository

import android.media.AudioDeviceAttributes
import android.media.Spatializer
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

interface SpatializerRepository {

    /**
     * Returns true when Spatial audio feature is supported for the [audioDeviceAttributes] and
     * false the otherwise.
     */
    suspend fun isAvailableForDevice(audioDeviceAttributes: AudioDeviceAttributes): Boolean

    /** Returns a list [AudioDeviceAttributes] that are compatible with spatial audio. */
    suspend fun getCompatibleDevices(): Collection<AudioDeviceAttributes>

    /** Adds a [audioDeviceAttributes] to [getCompatibleDevices] list. */
    suspend fun addCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes)

    /** Removes a [audioDeviceAttributes] to [getCompatibleDevices] list. */
    suspend fun removeCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes)
}

class SpatializerRepositoryImpl(
    private val spatializer: Spatializer,
    private val backgroundContext: CoroutineContext,
) : SpatializerRepository {

    override suspend fun isAvailableForDevice(
        audioDeviceAttributes: AudioDeviceAttributes
    ): Boolean {
        return withContext(backgroundContext) {
            spatializer.isAvailableForDevice(audioDeviceAttributes)
        }
    }

    override suspend fun getCompatibleDevices(): Collection<AudioDeviceAttributes> =
        withContext(backgroundContext) { spatializer.compatibleAudioDevices }

    override suspend fun addCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes) {
        withContext(backgroundContext) {
            spatializer.addCompatibleAudioDevice(audioDeviceAttributes)
        }
    }

    override suspend fun removeCompatibleDevice(audioDeviceAttributes: AudioDeviceAttributes) {
        withContext(backgroundContext) {
            spatializer.removeCompatibleAudioDevice(audioDeviceAttributes)
        }
    }
}
