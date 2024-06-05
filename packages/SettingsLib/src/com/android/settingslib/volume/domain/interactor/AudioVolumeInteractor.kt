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

package com.android.settingslib.volume.domain.interactor

import android.media.AudioManager
import com.android.settingslib.statusbar.notification.domain.interactor.NotificationsSoundPolicyInteractor
import com.android.settingslib.volume.data.repository.AudioRepository
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.settingslib.volume.shared.model.RingerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Provides audio stream state and an ability to change it */
class AudioVolumeInteractor(
    private val audioRepository: AudioRepository,
    private val notificationsSoundPolicyInteractor: NotificationsSoundPolicyInteractor,
) {

    /** State of the [AudioStream]. */
    fun getAudioStream(audioStream: AudioStream): Flow<AudioStreamModel> =
        combine(
            audioRepository.getAudioStream(audioStream),
            audioRepository.ringerMode,
            notificationsSoundPolicyInteractor.isZenMuted(audioStream)
        ) { streamModel: AudioStreamModel, ringerMode: RingerMode, isZenMuted: Boolean ->
            streamModel.copy(volume = processVolume(streamModel, ringerMode, isZenMuted))
        }

    val ringerMode: StateFlow<RingerMode>
        get() = audioRepository.ringerMode

    suspend fun setVolume(audioStream: AudioStream, volume: Int) {
        val streamModel = getAudioStream(audioStream).first()
        val oldVolume = streamModel.volume
        if (volume != oldVolume) {
            audioRepository.setVolume(audioStream, volume)
            when {
                volume == streamModel.minVolume -> setMuted(audioStream, true)
                oldVolume == streamModel.minVolume && volume > streamModel.minVolume ->
                    setMuted(audioStream, false)
            }
        }
    }

    suspend fun setMuted(audioStream: AudioStream, isMuted: Boolean) {
        if (audioStream.value == AudioManager.STREAM_RING) {
            val mode =
                if (isMuted) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            audioRepository.setRingerMode(audioStream, RingerMode(mode))
        }
        val mutedChanged = audioRepository.setMuted(audioStream, isMuted)
        if (mutedChanged && !isMuted) {
            with(getAudioStream(audioStream).first()) {
                if (volume == minVolume) {
                    // Slightly increase volume when user un-mutes the stream that is lowered
                    // down to its minimum
                    setVolume(audioStream, volume + 1)
                }
            }
        }
    }

    /** Checks if the volume can be changed via the UI. */
    fun canChangeVolume(audioStream: AudioStream): Flow<Boolean> {
        return if (audioStream.value == AudioManager.STREAM_NOTIFICATION) {
            combine(
                notificationsSoundPolicyInteractor.isZenMuted(audioStream),
                getAudioStream(AudioStream(AudioManager.STREAM_RING)).map { it.isMuted },
            ) { isZenMuted, isRingMuted ->
                !isZenMuted && !isRingMuted
            }
        } else {
            notificationsSoundPolicyInteractor.isZenMuted(audioStream).map { !it }
        }
    }

    private suspend fun processVolume(
        audioStreamModel: AudioStreamModel,
        ringerMode: RingerMode,
        isZenMuted: Boolean,
    ): Int {
        if (isZenMuted) {
            return audioRepository.getLastAudibleVolume(audioStreamModel.audioStream)
        }
        val isNotificationOrRing =
            audioStreamModel.audioStream.value == AudioManager.STREAM_RING ||
                audioStreamModel.audioStream.value == AudioManager.STREAM_NOTIFICATION
        if (isNotificationOrRing && ringerMode.value == AudioManager.RINGER_MODE_VIBRATE) {
            // For ringer-mode affected streams, show volume as zero when ringer mode is vibrate
            if (
                audioStreamModel.audioStream.value == AudioManager.STREAM_RING ||
                    (audioStreamModel.audioStream.value == AudioManager.STREAM_NOTIFICATION &&
                        audioStreamModel.isMuted)
            ) {
                return audioStreamModel.minVolume
            }
        } else if (audioStreamModel.isMuted) {
            return audioStreamModel.minVolume
        }
        return audioStreamModel.volume
    }
}
