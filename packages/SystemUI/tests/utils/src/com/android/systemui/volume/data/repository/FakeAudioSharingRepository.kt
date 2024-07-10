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

import androidx.annotation.IntRange
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MAX
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MIN
import com.android.settingslib.volume.data.repository.GroupIdToVolumes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeAudioSharingRepository : AudioSharingRepository {
    private val mutableInAudioSharing: MutableStateFlow<Boolean> = MutableStateFlow(false)
    private val mutableSecondaryGroupId: MutableStateFlow<Int> =
        MutableStateFlow(TEST_GROUP_ID_INVALID)
    private val mutableVolumeMap: MutableStateFlow<GroupIdToVolumes> = MutableStateFlow(emptyMap())

    override val inAudioSharing: Flow<Boolean> = mutableInAudioSharing
    override val secondaryGroupId: StateFlow<Int> = mutableSecondaryGroupId
    override val volumeMap: StateFlow<GroupIdToVolumes> = mutableVolumeMap

    override suspend fun setSecondaryVolume(volume: Int) {}

    fun setInAudioSharing(state: Boolean) {
        mutableInAudioSharing.value = state
    }

    fun setSecondaryGroupId(groupId: Int) {
        mutableSecondaryGroupId.value = groupId
    }

    fun setVolumeMap(volumeMap: GroupIdToVolumes) {
        mutableVolumeMap.value = volumeMap
    }

    private companion object {
        const val TEST_GROUP_ID_INVALID = -1
    }
}
