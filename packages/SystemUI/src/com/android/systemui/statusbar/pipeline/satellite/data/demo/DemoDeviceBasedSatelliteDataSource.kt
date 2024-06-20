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

import android.os.Bundle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Reads the incoming demo commands and emits the satellite-related commands to [satelliteEvents]
 * for the demo repository to consume.
 */
@SysUISingleton
class DemoDeviceBasedSatelliteDataSource
@Inject
constructor(
    demoModeController: DemoModeController,
    @Application scope: CoroutineScope,
) {
    private val demoCommandStream = demoModeController.demoFlowForCommand(DemoMode.COMMAND_NETWORK)
    private val _satelliteCommands =
        demoCommandStream.map { args -> args.toSatelliteEvent() }.filterNotNull()

    /** A flow that emits the demo commands that are satellite-related. */
    val satelliteEvents =
        _satelliteCommands.stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_VALUE)

    private fun Bundle.toSatelliteEvent(): DemoSatelliteEvent? {
        val satellite = getString("satellite") ?: return null
        if (satellite != "show") {
            return null
        }

        return DemoSatelliteEvent(
            connectionState = getString("connection").toConnectionState(),
            signalStrength = getString("level")?.toInt() ?: 0,
        )
    }

    data class DemoSatelliteEvent(
        val connectionState: SatelliteConnectionState,
        val signalStrength: Int,
    )

    private fun String?.toConnectionState(): SatelliteConnectionState {
        if (this == null) {
            return SatelliteConnectionState.Unknown
        }
        return try {
            // Lets people use "connected" on the command line and have it be correctly converted
            // to [SatelliteConnectionState.Connected] with a capital C.
            SatelliteConnectionState.valueOf(this.replaceFirstChar { it.uppercase() })
        } catch (e: IllegalArgumentException) {
            SatelliteConnectionState.Unknown
        }
    }

    private companion object {
        val DEFAULT_VALUE = DemoSatelliteEvent(SatelliteConnectionState.Unknown, signalStrength = 0)
    }
}
