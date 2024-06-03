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
import com.android.settingslib.media.session.defaultRemoteSessionChanged
import com.android.settingslib.volume.shared.AudioManagerEventsReceiver
import com.android.settingslib.volume.shared.model.AudioManagerEvent
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/** Provides controllers for currently active device media sessions. */
interface MediaControllerRepository {

    /**
     * Get a list of controllers for all ongoing sessions. The controllers will be provided in
     * priority order with the most important controller at index 0.
     *
     * This requires the [android.Manifest.permission.MEDIA_CONTENT_CONTROL] permission be held by
     * the calling app.
     */
    val activeSessions: StateFlow<List<MediaController>>
}

class MediaControllerRepositoryImpl(
    audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val mediaSessionManager: MediaSessionManager,
    localBluetoothManager: LocalBluetoothManager?,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : MediaControllerRepository {

    override val activeSessions: StateFlow<List<MediaController>> =
        merge(
                mediaSessionManager.defaultRemoteSessionChanged.map {
                    mediaSessionManager.getActiveSessions(null)
                },
                mediaSessionManager.activeMediaChanges.filterNotNull(),
                localBluetoothManager?.headsetAudioModeChanges?.map {
                    mediaSessionManager.getActiveSessions(null)
                } ?: emptyFlow(),
                audioManagerEventsReceiver.events
                    .filterIsInstance(AudioManagerEvent.StreamDevicesChanged::class)
                    .map { mediaSessionManager.getActiveSessions(null) },
            )
            .onStart { emit(mediaSessionManager.getActiveSessions(null)) }
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyList())
}
