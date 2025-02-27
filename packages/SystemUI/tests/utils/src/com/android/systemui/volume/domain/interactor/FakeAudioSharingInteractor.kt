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

package com.android.systemui.volume.domain.interactor

import android.content.Context
import androidx.annotation.IntRange
import com.android.dream.lowlight.dagger.qualifiers.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAudioSharingInteractor : AudioSharingInteractor {
    private val mutableInAudioSharing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val mutableVolume: MutableStateFlow<Int?> = MutableStateFlow(null)
    private var audioSharingVolumeBarAvailable = false

    override val isInAudioSharing: Flow<Boolean> = mutableInAudioSharing
    override val volume: Flow<Int?> = mutableVolume
    override val volumeMin: Int = AUDIO_SHARING_VOLUME_MIN
    override val volumeMax: Int = AUDIO_SHARING_VOLUME_MAX

    override suspend fun audioSharingVolumeBarAvailable(@Application context: Context): Boolean =
        audioSharingVolumeBarAvailable

    override fun setStreamVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        level: Int
    ) {}

    override fun handlePrimaryGroupChange() {}

    fun setInAudioSharing(state: Boolean) {
        mutableInAudioSharing.value = state
    }

    fun setVolume(volume: Int?) {
        mutableVolume.value = volume
    }

    fun setAudioSharingVolumeBarAvailable(available: Boolean) {
        audioSharingVolumeBarAvailable = available
    }

    companion object {
        const val AUDIO_SHARING_VOLUME_MIN = 0
        const val AUDIO_SHARING_VOLUME_MAX = 255
    }
}
