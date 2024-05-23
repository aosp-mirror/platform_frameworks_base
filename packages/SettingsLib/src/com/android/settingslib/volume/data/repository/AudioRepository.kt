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

package com.android.settingslib.volume.data.repository

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.OnCommunicationDeviceChangedListener
import com.android.internal.util.ConcurrentUtils
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import com.android.settingslib.volume.shared.model.AudioStream
import com.android.settingslib.volume.shared.model.AudioStreamModel
import com.android.settingslib.volume.shared.model.RingerMode
import com.android.settingslib.volume.shared.model.StreamAudioManagerEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Provides audio streams state and managing functionality. */
interface AudioRepository {

    /** Current [AudioManager.getMode]. */
    val mode: StateFlow<Int>

    /**
     * Ringtone mode.
     *
     * @see AudioManager.getRingerModeInternal
     */
    val ringerMode: StateFlow<RingerMode>

    /**
     * Communication device. Emits null when there is no communication device available.
     *
     * @see AudioDeviceInfo.getType
     */
    val communicationDevice: StateFlow<AudioDeviceInfo?>

    /** State of the [AudioStream]. */
    fun getAudioStream(audioStream: AudioStream): Flow<AudioStreamModel>

    /** Returns the last audible volume before stream was muted. */
    suspend fun getLastAudibleVolume(audioStream: AudioStream): Int

    suspend fun setVolume(audioStream: AudioStream, volume: Int)

    /**
     * Mutes and un-mutes [audioStream]. Returns true when the state changes and false the
     * otherwise.
     */
    suspend fun setMuted(audioStream: AudioStream, isMuted: Boolean): Boolean

    suspend fun setRingerMode(audioStream: AudioStream, mode: RingerMode)
}

class AudioRepositoryImpl(
    private val audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val audioManager: AudioManager,
    private val backgroundCoroutineContext: CoroutineContext,
    private val coroutineScope: CoroutineScope,
) : AudioRepository {

    override val mode: StateFlow<Int> =
        callbackFlow {
                val listener =
                    AudioManager.OnModeChangedListener { newMode -> launch { send(newMode) } }
                audioManager.addOnModeChangedListener(ConcurrentUtils.DIRECT_EXECUTOR, listener)
                awaitClose { audioManager.removeOnModeChangedListener(listener) }
            }
            .flowOn(backgroundCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), audioManager.mode)

    override val ringerMode: StateFlow<RingerMode> =
        audioManagerEventsReceiver.events
            .filterIsInstance(AudioManagerEvent.InternalRingerModeChanged::class)
            .map { RingerMode(audioManager.ringerModeInternal) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(),
                RingerMode(audioManager.ringerModeInternal),
            )

    override val communicationDevice: StateFlow<AudioDeviceInfo?>
        get() =
            callbackFlow {
                    val listener = OnCommunicationDeviceChangedListener { trySend(Unit) }
                    audioManager.addOnCommunicationDeviceChangedListener(
                        ConcurrentUtils.DIRECT_EXECUTOR,
                        listener,
                    )

                    awaitClose { audioManager.removeOnCommunicationDeviceChangedListener(listener) }
                }
                .filterNotNull()
                .map { audioManager.communicationDevice }
                .flowOn(backgroundCoroutineContext)
                .stateIn(
                    coroutineScope,
                    SharingStarted.WhileSubscribed(),
                    audioManager.communicationDevice,
                )

    override fun getAudioStream(audioStream: AudioStream): Flow<AudioStreamModel> {
        return audioManagerEventsReceiver.events
            .filter {
                if (it is StreamAudioManagerEvent) {
                    it.audioStream == audioStream
                } else {
                    true
                }
            }
            .map { getCurrentAudioStream(audioStream) }
            .onStart { emit(getCurrentAudioStream(audioStream)) }
            .conflate()
            .flowOn(backgroundCoroutineContext)
    }

    private fun getCurrentAudioStream(audioStream: AudioStream): AudioStreamModel {
        return AudioStreamModel(
            audioStream = audioStream,
            minVolume = getMinVolume(audioStream),
            maxVolume = audioManager.getStreamMaxVolume(audioStream.value),
            volume = audioManager.getStreamVolume(audioStream.value),
            isAffectedByMute = audioManager.isStreamAffectedByMute(audioStream.value),
            isAffectedByRingerMode = audioManager.isStreamAffectedByRingerMode(audioStream.value),
            isMuted = audioManager.isStreamMute(audioStream.value),
        )
    }

    override suspend fun getLastAudibleVolume(audioStream: AudioStream): Int {
        return withContext(backgroundCoroutineContext) {
            audioManager.getLastAudibleStreamVolume(audioStream.value)
        }
    }

    override suspend fun setVolume(audioStream: AudioStream, volume: Int) =
        withContext(backgroundCoroutineContext) {
            audioManager.setStreamVolume(audioStream.value, volume, 0)
        }

    override suspend fun setMuted(audioStream: AudioStream, isMuted: Boolean): Boolean {
        return withContext(backgroundCoroutineContext) {
            if (isMuted == audioManager.isStreamMute(audioStream.value)) {
                false
            } else {
                audioManager.adjustStreamVolume(
                    audioStream.value,
                    if (isMuted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
                true
            }
        }
    }

    override suspend fun setRingerMode(audioStream: AudioStream, mode: RingerMode) {
        withContext(backgroundCoroutineContext) { audioManager.ringerMode = mode.value }
    }

    private fun getMinVolume(stream: AudioStream): Int =
        try {
            audioManager.getStreamMinVolume(stream.value)
        } catch (e: IllegalArgumentException) {
            // Fallback to STREAM_VOICE_CALL because
            // CallVolumePreferenceController.java default
            // return STREAM_VOICE_CALL in getAudioStream
            audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
        }
}
