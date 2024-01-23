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

import com.android.settingslib.media.MediaDevice
import com.android.settingslib.volume.data.repository.LocalMediaRepository
import com.android.settingslib.volume.domain.model.RoutingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class LocalMediaInteractor(
    private val repository: LocalMediaRepository,
    coroutineScope: CoroutineScope,
) {

    /** Available devices list */
    val mediaDevices: StateFlow<Collection<MediaDevice>>
        get() = repository.mediaDevices

    /** Currently connected media device */
    val currentConnectedDevice: StateFlow<MediaDevice?>
        get() = repository.currentConnectedDevice

    val remoteRoutingSessions: StateFlow<List<RoutingSession>> =
        repository.remoteRoutingSessions
            .map { sessions ->
                sessions.map {
                    RoutingSession(
                        routingSessionInfo = it.routingSessionInfo,
                        isMediaOutputDisabled = it.isMediaOutputDisabled,
                        isVolumeSeekBarEnabled =
                            it.isVolumeSeekBarEnabled && it.routingSessionInfo.volumeMax > 0
                    )
                }
            }
            .stateIn(coroutineScope, SharingStarted.WhileSubscribed(), emptyList())

    suspend fun adjustSessionVolume(sessionId: String?, volume: Int) =
        repository.adjustSessionVolume(sessionId, volume)
}
