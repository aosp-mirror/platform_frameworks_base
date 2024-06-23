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

package com.android.settingslib.volume.shared

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import com.android.settingslib.volume.shared.model.AudioStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/** Exposes [AudioManager] events as a observable shared flow. */
interface AudioManagerEventsReceiver {

    val events: SharedFlow<AudioManagerEvent>
}

class AudioManagerEventsReceiverImpl(
    private val context: Context,
    coroutineScope: CoroutineScope,
) : AudioManagerEventsReceiver {

    private val allActions: Collection<String>
        get() =
            setOf(
                AudioManager.STREAM_MUTE_CHANGED_ACTION,
                AudioManager.MASTER_MUTE_CHANGED_ACTION,
                AudioManager.VOLUME_CHANGED_ACTION,
                AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION,
                AudioManager.STREAM_DEVICES_CHANGED_ACTION,
                AudioManager.ACTION_VOLUME_CHANGED,
            )

    override val events: SharedFlow<AudioManagerEvent> =
        callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            launch { send(intent) }
                        }
                    }
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        for (action in allActions) {
                            addAction(action)
                        }
                    }
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .filterNotNull()
            .filter { intent -> allActions.contains(intent.action) }
            .mapNotNull { it.toAudioManagerEvent() }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed())

    private fun Intent.toAudioManagerEvent(): AudioManagerEvent? {
        when (action) {
            AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION ->
                return AudioManagerEvent.InternalRingerModeChanged
            AudioManager.STREAM_DEVICES_CHANGED_ACTION ->
                return AudioManagerEvent.StreamDevicesChanged
            AudioManager.MASTER_MUTE_CHANGED_ACTION ->
                return AudioManagerEvent.StreamMasterMuteChanged
        }

        val audioStreamType: Int =
            getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, AudioManager.ERROR)
        if (audioStreamType == AudioManager.ERROR) {
            Log.e(
                "AudioManagerIntentsReceiver",
                "Intent doesn't have AudioManager.EXTRA_VOLUME_STREAM_TYPE extra",
            )
            return null
        }
        val audioStream = AudioStream(audioStreamType)
        return when (action) {
            AudioManager.STREAM_MUTE_CHANGED_ACTION ->
                AudioManagerEvent.StreamMuteChanged(audioStream)
            AudioManager.VOLUME_CHANGED_ACTION -> AudioManagerEvent.StreamVolumeChanged(audioStream)
            else -> null
        }
    }
}
