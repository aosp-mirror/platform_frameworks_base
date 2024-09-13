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

import android.content.ContentResolver
import android.database.ContentObserver
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioDeviceCategory
import android.media.AudioManager.OnCommunicationDeviceChangedListener
import android.media.IVolumeController
import android.provider.Settings
import android.util.Log
import androidx.concurrent.futures.DirectExecutor
import com.android.internal.util.ConcurrentUtils
import com.android.settingslib.volume.data.model.VolumeControllerEvent
import com.android.settingslib.volume.shared.AudioLogger
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
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

    /** Events from [AudioManager.setVolumeController] */
    val volumeControllerEvents: Flow<VolumeControllerEvent>

    fun init()

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

    suspend fun setRingerModeInternal(audioStream: AudioStream, mode: RingerMode)

    /** Gets audio device category. */
    @AudioDeviceCategory suspend fun getBluetoothAudioDeviceCategory(bluetoothAddress: String): Int

    suspend fun notifyVolumeControllerVisible(isVisible: Boolean)
}

class AudioRepositoryImpl(
    private val audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val audioManager: AudioManager,
    private val contentResolver: ContentResolver,
    private val backgroundCoroutineContext: CoroutineContext,
    private val coroutineScope: CoroutineScope,
    private val logger: AudioLogger,
    shouldUseVolumeController: Boolean,
) : AudioRepository {

    private val volumeController = ProducingVolumeController()
    private val streamSettingNames: Map<AudioStream, String> =
        mapOf(
            AudioStream(AudioManager.STREAM_VOICE_CALL) to Settings.System.VOLUME_VOICE,
            AudioStream(AudioManager.STREAM_SYSTEM) to Settings.System.VOLUME_SYSTEM,
            AudioStream(AudioManager.STREAM_RING) to Settings.System.VOLUME_RING,
            AudioStream(AudioManager.STREAM_MUSIC) to Settings.System.VOLUME_MUSIC,
            AudioStream(AudioManager.STREAM_ALARM) to Settings.System.VOLUME_ALARM,
            AudioStream(AudioManager.STREAM_NOTIFICATION) to Settings.System.VOLUME_NOTIFICATION,
            AudioStream(AudioManager.STREAM_BLUETOOTH_SCO) to Settings.System.VOLUME_BLUETOOTH_SCO,
            AudioStream(AudioManager.STREAM_ACCESSIBILITY) to Settings.System.VOLUME_ACCESSIBILITY,
            AudioStream(AudioManager.STREAM_ASSISTANT) to Settings.System.VOLUME_ASSISTANT,
        )

    override val volumeControllerEvents: Flow<VolumeControllerEvent> =
        if (shouldUseVolumeController) {
            volumeController.events
        } else {
            emptyFlow()
        }

    override val mode: StateFlow<Int> =
        callbackFlow {
                val listener = AudioManager.OnModeChangedListener { newMode -> trySend(newMode) }
                audioManager.addOnModeChangedListener(ConcurrentUtils.DIRECT_EXECUTOR, listener)
                awaitClose { audioManager.removeOnModeChangedListener(listener) }
            }
            .onStart { emit(audioManager.mode) }
            .flowOn(backgroundCoroutineContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), audioManager.mode)

    override val ringerMode: StateFlow<RingerMode> =
        audioManagerEventsReceiver.events
            .filterIsInstance(AudioManagerEvent.InternalRingerModeChanged::class)
            .map { RingerMode(audioManager.ringerModeInternal) }
            .onStart { emit(RingerMode(audioManager.ringerModeInternal)) }
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
                .onStart { emit(audioManager.communicationDevice) }
                .flowOn(backgroundCoroutineContext)
                .stateIn(
                    coroutineScope,
                    SharingStarted.WhileSubscribed(),
                    audioManager.communicationDevice,
                )

    override fun init() {
        try {
            audioManager.volumeController = volumeController
        } catch (error: SecurityException) {
            Log.wtf("AudioManager", "Unable to set the volume controller", error)
        }
    }

    override fun getAudioStream(audioStream: AudioStream): Flow<AudioStreamModel> {
        return merge(
                audioManagerEventsReceiver.events.filter {
                    if (it is StreamAudioManagerEvent) {
                        it.audioStream == audioStream
                    } else {
                        true
                    }
                },
                volumeSettingChanges(audioStream),
                volumeControllerEvents.filter { it is VolumeControllerEvent.VolumeChanged },
            )
            .conflate()
            .map { getCurrentAudioStream(audioStream) }
            .onStart { emit(getCurrentAudioStream(audioStream)) }
            .distinctUntilChanged()
            .onEach { logger.onVolumeUpdateReceived(audioStream, it) }
            .flowOn(backgroundCoroutineContext)
    }

    private fun getCurrentAudioStream(audioStream: AudioStream): AudioStreamModel {
        return AudioStreamModel(
            audioStream = audioStream,
            minVolume = getMinVolume(audioStream),
            maxVolume = audioManager.getStreamMaxVolume(audioStream.value),
            volume = audioManager.getStreamVolume(audioStream.value),
            isAffectedByMute = audioManager.isStreamMutableByUi(audioStream.value),
            isAffectedByRingerMode = audioManager.isStreamAffectedByRingerMode(audioStream.value),
            isMuted = audioManager.isStreamMute(audioStream.value),
        )
    }

    override suspend fun getLastAudibleVolume(audioStream: AudioStream): Int {
        return withContext(backgroundCoroutineContext) {
            audioManager.getLastAudibleStreamVolume(audioStream.value)
        }
    }

    override suspend fun setVolume(audioStream: AudioStream, volume: Int) {
        withContext(backgroundCoroutineContext) {
            logger.onSetVolumeRequested(audioStream, volume)
            audioManager.setStreamVolume(audioStream.value, volume, 0)
        }
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

    override suspend fun setRingerModeInternal(audioStream: AudioStream, mode: RingerMode) {
        withContext(backgroundCoroutineContext) { audioManager.ringerModeInternal = mode.value }
    }

    @AudioDeviceCategory
    override suspend fun getBluetoothAudioDeviceCategory(bluetoothAddress: String): Int {
        return withContext(backgroundCoroutineContext) {
            audioManager.getBluetoothAudioDeviceCategory(bluetoothAddress)
        }
    }

    override suspend fun notifyVolumeControllerVisible(isVisible: Boolean) {
        withContext(backgroundCoroutineContext) {
            audioManager.notifyVolumeControllerVisible(volumeController, isVisible)
        }
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

    private fun volumeSettingChanges(audioStream: AudioStream): Flow<Unit> {
        val uri = streamSettingNames[audioStream]?.let(Settings.System::getUriFor)
        uri ?: return emptyFlow()
        return callbackFlow {
            val observer =
                object : ContentObserver(DirectExecutor.INSTANCE, 0) {
                    override fun onChange(selfChange: Boolean) {
                        launch { send(Unit) }
                    }
                }
            contentResolver.registerContentObserver(uri, false, observer)
            awaitClose { contentResolver.unregisterContentObserver(observer) }
        }
    }
}

private class ProducingVolumeController : IVolumeController.Stub() {

    private val mutableEvents = MutableSharedFlow<VolumeControllerEvent>(extraBufferCapacity = 32)
    val events = mutableEvents.asSharedFlow()

    override fun displaySafeVolumeWarning(flags: Int) {
        mutableEvents.tryEmit(VolumeControllerEvent.DisplaySafeVolumeWarning(flags))
    }

    override fun volumeChanged(streamType: Int, flags: Int) {
        mutableEvents.tryEmit(VolumeControllerEvent.VolumeChanged(streamType, flags))
    }

    override fun masterMuteChanged(flags: Int) {
        mutableEvents.tryEmit(VolumeControllerEvent.MasterMuteChanged(flags))
    }

    override fun setLayoutDirection(layoutDirection: Int) {
        mutableEvents.tryEmit(VolumeControllerEvent.SetLayoutDirection(layoutDirection))
    }

    override fun dismiss() {
        mutableEvents.tryEmit(VolumeControllerEvent.Dismiss)
    }

    override fun setA11yMode(mode: Int) {
        mutableEvents.tryEmit(VolumeControllerEvent.SetA11yMode(mode))
    }

    override fun displayCsdWarning(
        csdWarning: Int,
        displayDurationMs: Int,
    ) {
        mutableEvents.tryEmit(
            VolumeControllerEvent.DisplayCsdWarning(
                csdWarning,
                displayDurationMs,
            )
        )
    }
}
