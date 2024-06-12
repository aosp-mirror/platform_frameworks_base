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

package com.android.systemui.statusbar.pipeline.satellite.data.demo

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** A satellite repository that represents the latest satellite values sent via demo mode. */
@SysUISingleton
class DemoDeviceBasedSatelliteRepository
@Inject
constructor(
    private val dataSource: DemoDeviceBasedSatelliteDataSource,
    @Application private val scope: CoroutineScope,
) : DeviceBasedSatelliteRepository {
    private var demoCommandJob: Job? = null

    override val isSatelliteProvisioned = MutableStateFlow(true)
    override val connectionState = MutableStateFlow(SatelliteConnectionState.Unknown)
    override val signalStrength = MutableStateFlow(0)
    override val isSatelliteAllowedForCurrentLocation = MutableStateFlow(true)

    fun startProcessingCommands() {
        demoCommandJob =
            scope.launch { dataSource.satelliteEvents.collect { event -> processEvent(event) } }
    }

    fun stopProcessingCommands() {
        demoCommandJob?.cancel()
    }

    private fun processEvent(event: DemoDeviceBasedSatelliteDataSource.DemoSatelliteEvent) {
        connectionState.value = event.connectionState
        signalStrength.value = event.signalStrength
    }
}
