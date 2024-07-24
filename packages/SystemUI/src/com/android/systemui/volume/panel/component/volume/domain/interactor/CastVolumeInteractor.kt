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

package com.android.systemui.volume.panel.component.volume.domain.interactor

import com.android.settingslib.volume.domain.interactor.LocalMediaInteractor
import com.android.settingslib.volume.domain.model.RoutingSession
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Provides a remote media casting state. */
@VolumePanelScope
class CastVolumeInteractor
@Inject
constructor(
    @VolumePanelScope private val coroutineScope: CoroutineScope,
    private val localMediaInteractor: LocalMediaInteractor,
) {

    /** Returns a list of [RoutingSession] to show in the UI. */
    val remoteRoutingSessions: StateFlow<List<RoutingSession>> =
        localMediaInteractor.remoteRoutingSessions
            .map { it.filter { routingSession -> routingSession.isVolumeSeekBarEnabled } }
            .stateIn(coroutineScope, SharingStarted.Eagerly, emptyList())

    /** Sets [routingSession] volume to [volume]. */
    suspend fun setVolume(routingSession: RoutingSession, volume: Int) {
        localMediaInteractor.adjustSessionVolume(routingSession.routingSessionInfo.id, volume)
    }
}
