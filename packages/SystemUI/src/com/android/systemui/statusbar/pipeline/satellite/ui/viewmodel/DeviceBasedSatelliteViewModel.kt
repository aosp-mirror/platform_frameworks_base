/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.satellite.ui.viewmodel

import android.content.Context
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.dagger.OemSatelliteInputLog
import com.android.systemui.statusbar.pipeline.satellite.domain.interactor.DeviceBasedSatelliteInteractor
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * View-Model for the device-based satellite icon. This icon will only show in the status bar if
 * satellite is available AND all other service states are considered OOS.
 */
interface DeviceBasedSatelliteViewModel {
    /**
     * The satellite icon that should be displayed, or null if no satellite icon should be
     * displayed.
     */
    val icon: StateFlow<Icon?>

    /**
     * The satellite-related text that should be used as the carrier text string when satellite is
     * active, or null if the carrier text string shouldn't include any satellite information.
     */
    val carrierText: StateFlow<String?>
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceBasedSatelliteViewModelImpl
@Inject
constructor(
    context: Context,
    interactor: DeviceBasedSatelliteInteractor,
    @Application scope: CoroutineScope,
    airplaneModeRepository: AirplaneModeRepository,
    @OemSatelliteInputLog logBuffer: LogBuffer,
) : DeviceBasedSatelliteViewModel {
    private val shouldShowIcon: Flow<Boolean> =
        interactor.areAllConnectionsOutOfService.flatMapLatest { allOos ->
            if (!allOos) {
                flowOf(false)
            } else {
                combine(
                    interactor.isSatelliteAllowed,
                    interactor.isDeviceProvisioned,
                    interactor.isWifiActive,
                    airplaneModeRepository.isAirplaneMode
                ) { isSatelliteAllowed, isDeviceProvisioned, isWifiActive, isAirplaneMode ->
                    isSatelliteAllowed && isDeviceProvisioned && !isWifiActive && !isAirplaneMode
                }
            }
        }

    // This adds a 10 seconds delay before showing the icon
    private val shouldActuallyShowIcon: StateFlow<Boolean> =
        shouldShowIcon
            .distinctUntilChanged()
            .flatMapLatest { shouldShow ->
                if (shouldShow) {
                    logBuffer.log(
                        TAG,
                        LogLevel.INFO,
                        { long1 = DELAY_DURATION.inWholeSeconds },
                        { "Waiting $long1 seconds before showing the satellite icon" }
                    )
                    delay(DELAY_DURATION)
                    flowOf(true)
                } else {
                    flowOf(false)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val icon: StateFlow<Icon?> =
        combine(
                shouldActuallyShowIcon,
                interactor.connectionState,
                interactor.signalStrength,
            ) { shouldShow, state, signalStrength ->
                if (shouldShow) {
                    SatelliteIconModel.fromConnectionState(state, signalStrength)
                } else {
                    null
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val carrierText: StateFlow<String?> =
        combine(
                shouldActuallyShowIcon,
                interactor.connectionState,
            ) { shouldShow, connectionState ->
                if (shouldShow) {
                    when (connectionState) {
                        SatelliteConnectionState.Connected ->
                            context.getString(R.string.satellite_connected_carrier_text)
                        SatelliteConnectionState.On ->
                            context.getString(R.string.satellite_not_connected_carrier_text)
                        SatelliteConnectionState.Off,
                        SatelliteConnectionState.Unknown -> null
                    }
                } else {
                    null
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    companion object {
        private const val TAG = "DeviceBasedSatelliteViewModel"
        private val DELAY_DURATION = 10.seconds
    }
}
