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

package com.android.systemui.volume.data.repository

import com.android.settingslib.media.MediaDevice
import com.android.settingslib.volume.data.model.RoutingSession
import com.android.settingslib.volume.data.repository.LocalMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeLocalMediaRepository : LocalMediaRepository {

    private val volumeBySession: MutableMap<String?, Int> = mutableMapOf()

    private val mutableMediaDevices = MutableStateFlow<List<MediaDevice>>(emptyList())
    override val mediaDevices: StateFlow<List<MediaDevice>>
        get() = mutableMediaDevices.asStateFlow()

    private val mutableCurrentConnectedDevice = MutableStateFlow<MediaDevice?>(null)
    override val currentConnectedDevice: StateFlow<MediaDevice?>
        get() = mutableCurrentConnectedDevice.asStateFlow()

    private val mutableRemoteRoutingSessions = MutableStateFlow<List<RoutingSession>>(emptyList())
    override val remoteRoutingSessions: StateFlow<List<RoutingSession>>
        get() = mutableRemoteRoutingSessions.asStateFlow()

    fun updateMediaDevices(devices: List<MediaDevice>) {
        mutableMediaDevices.value = devices
    }

    fun updateCurrentConnectedDevice(device: MediaDevice?) {
        mutableCurrentConnectedDevice.value = device
    }

    fun updateRemoteRoutingSessions(sessions: List<RoutingSession>) {
        mutableRemoteRoutingSessions.value = sessions
    }

    fun getSessionVolume(sessionId: String?): Int = volumeBySession.getOrDefault(sessionId, 0)

    override suspend fun adjustSessionVolume(sessionId: String?, volume: Int) {
        volumeBySession[sessionId] = volume
    }
}
