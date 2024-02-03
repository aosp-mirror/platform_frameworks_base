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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.headsetAudioModeChanges
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Provides controllers for currently active device media sessions. */
interface MediaControllerRepository {

    /** Current [MediaController]. Null is emitted when there is no active [MediaController]. */
    val activeMediaController: StateFlow<MediaController?>
}

class MediaControllerRepositoryImpl(
    private val context: Context,
    private val mediaSessionManager: MediaSessionManager,
    localBluetoothManager: LocalBluetoothManager?,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : MediaControllerRepository {

    private val devicesChanges: Flow<Unit> =
        callbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (AudioManager.STREAM_DEVICES_CHANGED_ACTION == intent?.action) {
                                launch { send(Unit) }
                            }
                        }
                    }
                context.registerReceiver(
                    receiver,
                    IntentFilter(AudioManager.STREAM_DEVICES_CHANGED_ACTION)
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .shareIn(coroutineScope, SharingStarted.WhileSubscribed(), replay = 0)

    override val activeMediaController: StateFlow<MediaController?> =
        combine(
                localBluetoothManager?.headsetAudioModeChanges?.onStart { emit(Unit) }
                    ?: emptyFlow(),
                devicesChanges.onStart { emit(Unit) },
            ) { _, _ ->
                getActiveLocalMediaController()
            }
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

    private fun getActiveLocalMediaController(): MediaController? {
        var localController: MediaController? = null
        val remoteMediaSessionLists: MutableList<String> = ArrayList()
        for (controller in mediaSessionManager.getActiveSessions(null)) {
            val playbackInfo: MediaController.PlaybackInfo = controller.playbackInfo ?: continue
            val playbackState = controller.playbackState ?: continue
            if (inactivePlaybackStates.contains(playbackState.state)) {
                continue
            }
            when (playbackInfo.playbackType) {
                MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE -> {
                    if (localController?.packageName.equals(controller.packageName)) {
                        localController = null
                    }
                    if (!remoteMediaSessionLists.contains(controller.packageName)) {
                        remoteMediaSessionLists.add(controller.packageName)
                    }
                }
                MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL -> {
                    if (
                        localController == null &&
                            !remoteMediaSessionLists.contains(controller.packageName)
                    ) {
                        localController = controller
                    }
                }
            }
        }
        return localController
    }

    private companion object {
        val inactivePlaybackStates =
            setOf(PlaybackState.STATE_STOPPED, PlaybackState.STATE_NONE, PlaybackState.STATE_ERROR)
    }
}
