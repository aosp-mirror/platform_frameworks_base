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
import kotlinx.coroutines.flow.StateFlow

class SpatializerInteractor(private val repository: SpatializerRepository) {

    /** Checks if head tracking is available. */
    val isHeadTrackingAvailable: StateFlow<Boolean>
        get() = repository.isHeadTrackingAvailable

    suspend fun isSpatialAudioAvailable(audioDeviceAttributes: AudioDeviceAttributes): Boolean =
        repository.isSpatialAudioAvailableForDevice(audioDeviceAttributes)

    /** Checks if spatial audio is enabled for the [audioDeviceAttributes]. */
    suspend fun isSpatialAudioEnabled(audioDeviceAttributes: AudioDeviceAttributes): Boolean =
        repository.getSpatialAudioCompatibleDevices().contains(audioDeviceAttributes)

    /** Enables or disables spatial audio for [audioDeviceAttributes]. */
    suspend fun setSpatialAudioEnabled(
        audioDeviceAttributes: AudioDeviceAttributes,
        isEnabled: Boolean
    ) {
        if (isEnabled) {
            repository.addSpatialAudioCompatibleDevice(audioDeviceAttributes)
        } else {
            repository.removeSpatialAudioCompatibleDevice(audioDeviceAttributes)
        }
    }

    /** Checks if head tracking is enabled for the [audioDeviceAttributes]. */
    suspend fun isHeadTrackingEnabled(audioDeviceAttributes: AudioDeviceAttributes): Boolean =
        repository.isHeadTrackingEnabled(audioDeviceAttributes)

    /** Enables or disables head tracking for the [audioDeviceAttributes]. */
    suspend fun setHeadTrackingEnabled(
        audioDeviceAttributes: AudioDeviceAttributes,
        isEnabled: Boolean,
    ) = repository.setHeadTrackingEnabled(audioDeviceAttributes, isEnabled)
}
