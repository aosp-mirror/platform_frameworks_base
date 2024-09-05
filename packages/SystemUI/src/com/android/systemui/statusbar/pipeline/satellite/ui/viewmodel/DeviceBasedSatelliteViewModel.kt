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
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.dagger.DeviceBasedSatelliteInputLog
import com.android.systemui.statusbar.pipeline.dagger.DeviceBasedSatelliteTableLog
import com.android.systemui.statusbar.pipeline.satellite.domain.interactor.DeviceBasedSatelliteInteractor
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.statusbar.pipeline.satellite.ui.model.SatelliteIconModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
@SysUISingleton
class DeviceBasedSatelliteViewModelImpl
@Inject
constructor(
    context: Context,
    interactor: DeviceBasedSatelliteInteractor,
    @Application scope: CoroutineScope,
    airplaneModeRepository: AirplaneModeRepository,
    @DeviceBasedSatelliteInputLog logBuffer: LogBuffer,
    @DeviceBasedSatelliteTableLog tableLog: TableLogBuffer,
) : DeviceBasedSatelliteViewModel {

    // This adds a 10 seconds delay before showing the icon
    private val shouldShowIconForOosAfterHysteresis: StateFlow<Boolean> =
        interactor.areAllConnectionsOutOfService
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
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLog,
                columnPrefix = "vm",
                columnName = COL_VISIBLE_FOR_OOS,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private val canShowIcon =
        combine(
            interactor.isSatelliteAllowed,
            interactor.isSatelliteProvisioned,
        ) { allowed, provisioned ->
            allowed && provisioned
        }

    private val showIcon =
        canShowIcon
            .flatMapLatest { canShow ->
                if (!canShow) {
                    flowOf(false)
                } else {
                    combine(
                        shouldShowIconForOosAfterHysteresis,
                        interactor.connectionState,
                        interactor.isWifiActive,
                        airplaneModeRepository.isAirplaneMode,
                    ) { showForOos, connectionState, isWifiActive, isAirplaneMode ->
                        if (isWifiActive || isAirplaneMode) {
                            false
                        } else {
                            showForOos ||
                                connectionState == SatelliteConnectionState.On ||
                                connectionState == SatelliteConnectionState.Connected
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLog,
                columnPrefix = "vm",
                columnName = COL_VISIBLE,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val icon: StateFlow<Icon?> =
        combine(
                showIcon,
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
                showIcon,
                interactor.connectionState,
            ) { shouldShow, connectionState ->
                logBuffer.log(
                    TAG,
                    LogLevel.INFO,
                    {
                        bool1 = shouldShow
                        str1 = connectionState.name
                    },
                    { "Updating carrier text. shouldShow=$bool1 connectionState=$str1" }
                )
                if (shouldShow) {
                    when (connectionState) {
                        SatelliteConnectionState.On,
                        SatelliteConnectionState.Connected ->
                            context.getString(R.string.satellite_connected_carrier_text)
                        SatelliteConnectionState.Off,
                        SatelliteConnectionState.Unknown -> {
                            // If we're showing the satellite icon opportunistically, use the
                            // emergency-only version of the carrier string
                            context.getString(R.string.satellite_emergency_only_carrier_text)
                        }
                    }
                } else {
                    null
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLog,
                columnPrefix = "vm",
                columnName = COL_CARRIER_TEXT,
                initialValue = null,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    companion object {
        private const val TAG = "DeviceBasedSatelliteViewModel"
        private val DELAY_DURATION = 10.seconds

        const val COL_VISIBLE_FOR_OOS = "visibleForOos"
        const val COL_VISIBLE = "visible"
        const val COL_CARRIER_TEXT = "carrierText"
    }
}
