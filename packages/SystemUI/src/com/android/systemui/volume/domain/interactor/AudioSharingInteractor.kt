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

import android.bluetooth.BluetoothCsipSetCoordinator
import androidx.annotation.IntRange
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MAX
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MIN
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

interface AudioSharingInteractor {
    /** Audio sharing secondary headset volume changes. */
    val volume: Flow<Int?>

    /** Audio sharing secondary headset min volume. */
    val volumeMin: Int

    /** Audio sharing secondary headset max volume. */
    val volumeMax: Int

    /** Set the volume of the secondary headset in audio sharing. */
    fun setStreamVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        level: Int
    )
}

@SysUISingleton
class AudioSharingInteractorImpl
@Inject
constructor(
    @Application private val coroutineScope: CoroutineScope,
    private val audioSharingRepository: AudioSharingRepository
) : AudioSharingInteractor {

    override val volume: Flow<Int?> =
        combine(audioSharingRepository.secondaryGroupId, audioSharingRepository.volumeMap) {
            secondaryGroupId,
            volumeMap ->
            if (secondaryGroupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) null
            else volumeMap.getOrDefault(secondaryGroupId, DEFAULT_VOLUME)
        }

    override val volumeMin: Int = AUDIO_SHARING_VOLUME_MIN

    override val volumeMax: Int = AUDIO_SHARING_VOLUME_MAX

    override fun setStreamVolume(level: Int) {
        coroutineScope.launch { audioSharingRepository.setSecondaryVolume(level) }
    }

    private companion object {
        const val DEFAULT_VOLUME = 20
    }
}

@SysUISingleton
class AudioSharingInteractorEmptyImpl : AudioSharingInteractor {
    override val volume: Flow<Int?> = emptyFlow()
    override val volumeMin: Int = EMPTY_VOLUME
    override val volumeMax: Int = EMPTY_VOLUME

    override fun setStreamVolume(level: Int) {}

    private companion object {
        const val EMPTY_VOLUME = 0
    }
}
