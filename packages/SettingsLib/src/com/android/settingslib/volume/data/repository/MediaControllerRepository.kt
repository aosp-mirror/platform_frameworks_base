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

    /** Current [MediaController]. Null is emitted when there is no active [MediaController]. */
    val activeMediaControllers: StateFlow<Collection<MediaController>>
}

class MediaControllerRepositoryImpl(
    audioManagerEventsReceiver: AudioManagerEventsReceiver,
    private val mediaSessionManager: MediaSessionManager,
    localBluetoothManager: LocalBluetoothManager?,
    coroutineScope: CoroutineScope,
    backgroundContext: CoroutineContext,
) : MediaControllerRepository {

    override val activeMediaControllers: StateFlow<Collection<MediaController>> =
        merge(
                mediaSessionManager.activeMediaChanges
                    .onStart { emit(mediaSessionManager.getActiveSessions(null)) }
                    .filterNotNull(),
                localBluetoothManager
                    ?.headsetAudioModeChanges
                    ?.onStart { emit(Unit) }
                    ?.map { mediaSessionManager.getActiveSessions(null) } ?: emptyFlow(),
                audioManagerEventsReceiver.events
                    .filterIsInstance(AudioManagerEvent.StreamDevicesChanged::class)
                    .onStart { emit(AudioManagerEvent.StreamDevicesChanged) }
                    .map { mediaSessionManager.getActiveSessions(null) },
            )
            .flowOn(backgroundContext)
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyList())
}
