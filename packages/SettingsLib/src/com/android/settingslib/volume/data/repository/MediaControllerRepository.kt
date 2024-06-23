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

import android.media.session.MediaController
import android.media.session.MediaSessionManager
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.bluetooth.headsetAudioModeChanges
import com.android.settingslib.media.session.activeMediaChanges
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Provides controllers for currently active device media sessions. */
interface MediaControllerRepository {

    /** Current [MediaController]. Null is emitted when there is no active [MediaController]. */
    val activeLocalMediaController: StateFlow<MediaController?>
}

class MediaControllerRepositoryImpl(
    audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val mediaSessionManager: MediaSessionManager,
    localBluetoothManager: LocalBluetoothManager?,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : MediaControllerRepository {

    private val devicesChanges =
        audioManagerEventsReceiver.events.filterIsInstance(
            AudioManagerEvent.StreamDevicesChanged::class
        )

    override val activeLocalMediaController: StateFlow<MediaController?> =
        combine(
                mediaSessionManager.activeMediaChanges.onStart {
                    emit(mediaSessionManager.getActiveSessions(null))
                },
                localBluetoothManager?.headsetAudioModeChanges?.onStart { emit(Unit) }
                    ?: flowOf(null),
                devicesChanges.onStart { emit(AudioManagerEvent.StreamDevicesChanged) },
            ) { controllers, _, _ ->
                controllers?.let(::findLocalMediaController)
            }
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), null)

    private fun findLocalMediaController(
        controllers: Collection<MediaController>,
    ): MediaController? {
        var localController: MediaController? = null
        val remoteMediaSessionLists: MutableList<String> = ArrayList()
        for (controller in controllers) {
            val playbackInfo: MediaController.PlaybackInfo = controller.playbackInfo ?: continue
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
}
