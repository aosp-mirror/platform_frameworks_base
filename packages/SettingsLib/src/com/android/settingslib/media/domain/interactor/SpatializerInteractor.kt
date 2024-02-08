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

class SpatializerInteractor(private val repository: SpatializerRepository) {

    suspend fun isAvailable(audioDeviceAttributes: AudioDeviceAttributes): Boolean =
        repository.isAvailableForDevice(audioDeviceAttributes)

    /** Checks if spatial audio is enabled for the [audioDeviceAttributes]. */
    suspend fun isEnabled(audioDeviceAttributes: AudioDeviceAttributes): Boolean =
        repository.getCompatibleDevices().contains(audioDeviceAttributes)

    /** Enblaes or disables spatial audio for [audioDeviceAttributes]. */
    suspend fun setEnabled(audioDeviceAttributes: AudioDeviceAttributes, isEnabled: Boolean) {
        if (isEnabled) {
            repository.addCompatibleDevice(audioDeviceAttributes)
        } else {
            repository.removeCompatibleDevice(audioDeviceAttributes)
        }
    }
}
