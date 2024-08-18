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
import android.media.AudioManager.STREAM_MUSIC
import androidx.annotation.IntRange
import com.android.settingslib.volume.data.repository.AudioSharingRepository
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MAX
import com.android.settingslib.volume.data.repository.AudioSharingRepository.Companion.AUDIO_SHARING_VOLUME_MIN
import com.android.settingslib.volume.domain.interactor.AudioVolumeInteractor
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface AudioSharingInteractor {
    /** Audio sharing state on the device. */
    val isInAudioSharing: Flow<Boolean>

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

    /**
     * Handle primary group change in audio sharing.
     *
     * Once the primary group is changed, we need to sync its volume to STREAM_MUSIC to make sure
     * the volume adjustment during audio sharing can be kept after the sharing ends.
     *
     * TODO(b/355396988) Migrate to audio framework solution once it is in place.
     */
    fun handlePrimaryGroupChange()
}

@SysUISingleton
class AudioSharingInteractorImpl
@Inject
constructor(
    @Application private val coroutineScope: CoroutineScope,
    @Background private val backgroundCoroutineContext: CoroutineContext,
    private val audioVolumeInteractor: AudioVolumeInteractor,
    private val audioSharingRepository: AudioSharingRepository
) : AudioSharingInteractor {
    override val isInAudioSharing: Flow<Boolean> = audioSharingRepository.inAudioSharing

    override val volume: Flow<Int?> =
        combine(audioSharingRepository.secondaryGroupId, audioSharingRepository.volumeMap) {
                secondaryGroupId,
                volumeMap ->
                if (secondaryGroupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) null
                else volumeMap.getOrDefault(secondaryGroupId, DEFAULT_VOLUME)
            }
            .distinctUntilChanged()

    override val volumeMin: Int = AUDIO_SHARING_VOLUME_MIN

    override val volumeMax: Int = AUDIO_SHARING_VOLUME_MAX

    override fun setStreamVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        level: Int
    ) {
        coroutineScope.launch { audioSharingRepository.setSecondaryVolume(level) }
    }

    override fun handlePrimaryGroupChange() {
        coroutineScope.launch {
            audioSharingRepository.primaryGroupId
                .map { primaryGroupId -> audioSharingRepository.volumeMap.value[primaryGroupId] }
                .filterNotNull()
                .distinctUntilChanged()
                .collect {
                    // Once primary device change, we need to update the STREAM_MUSIC volume to get
                    // align with the primary device's volume
                    setMusicStreamVolume(it)
                }
        }
    }

    private suspend fun setMusicStreamVolume(volume: Int) {
        withContext(backgroundCoroutineContext) {
            val musicStream =
                audioVolumeInteractor.getAudioStream(AudioStream(STREAM_MUSIC)).first()
            val musicVolume =
                Math.round(
                    volume.toFloat() * (musicStream.maxVolume - musicStream.minVolume) /
                        (AUDIO_SHARING_VOLUME_MAX - AUDIO_SHARING_VOLUME_MIN)
                )
            audioVolumeInteractor.setVolume(AudioStream(STREAM_MUSIC), musicVolume)
        }
    }

    private companion object {
        const val DEFAULT_VOLUME = 20
    }
}

@SysUISingleton
class AudioSharingInteractorEmptyImpl @Inject constructor() : AudioSharingInteractor {
    override val isInAudioSharing: Flow<Boolean> = flowOf(false)
    override val volume: Flow<Int?> = emptyFlow()
    override val volumeMin: Int = EMPTY_VOLUME
    override val volumeMax: Int = EMPTY_VOLUME

    override fun setStreamVolume(
        @IntRange(from = AUDIO_SHARING_VOLUME_MIN.toLong(), to = AUDIO_SHARING_VOLUME_MAX.toLong())
        level: Int
    ) {}

    override fun handlePrimaryGroupChange() {}

    private companion object {
        const val EMPTY_VOLUME = 0
    }
}
