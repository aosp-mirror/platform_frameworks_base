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

import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.headsetAudioModeChanges
import com.android.settingslib.volume.shared.AudioManagerIntentsReceiver
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Provides controllers for currently active device media sessions. */
interface MediaControllerRepository {

    /** Current [MediaController]. Null is emitted when there is no active [MediaController]. */
    val activeMediaController: StateFlow<MediaController?>
}

class MediaControllerRepositoryImpl(
    audioManagerIntentsReceiver: AudioManagerIntentsReceiver,
    private val mediaSessionManager: MediaSessionManager,
    localBluetoothManager: LocalBluetoothManager?,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : MediaControllerRepository {

    private val devicesChanges =
        audioManagerIntentsReceiver.intents.filter {
            AudioManager.STREAM_DEVICES_CHANGED_ACTION == it.action
        }
    override val activeMediaController: StateFlow<MediaController?> =
        buildList {
                localBluetoothManager?.headsetAudioModeChanges?.let { add(it) }
                add(devicesChanges)
            }
            .merge()
            .onStart { emit(Unit) }
            .map { getActiveLocalMediaController() }
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
