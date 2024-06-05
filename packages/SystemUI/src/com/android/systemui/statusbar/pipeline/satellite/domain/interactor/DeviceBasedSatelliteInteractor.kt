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

package com.android.systemui.statusbar.pipeline.satellite.domain.interactor

import com.android.internal.telephony.flags.Flags
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.DeviceBasedSatelliteInputLog
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.satellite.data.DeviceBasedSatelliteRepository
import com.android.systemui.statusbar.pipeline.satellite.shared.model.SatelliteConnectionState
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.policy.domain.interactor.DeviceProvisioningInteractor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@SysUISingleton
class DeviceBasedSatelliteInteractor
@Inject
constructor(
    val repo: DeviceBasedSatelliteRepository,
    iconsInteractor: MobileIconsInteractor,
    deviceProvisioningInteractor: DeviceProvisioningInteractor,
    wifiInteractor: WifiInteractor,
    @Application scope: CoroutineScope,
    @DeviceBasedSatelliteInputLog private val logBuffer: LogBuffer,
) {
    /** Must be observed by any UI showing Satellite iconography */
    val isSatelliteAllowed =
        if (Flags.oemEnabledSatelliteFlag()) {
                repo.isSatelliteAllowedForCurrentLocation
            } else {
                flowOf(false)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    /** See [SatelliteConnectionState] for relevant states */
    val connectionState =
        if (Flags.oemEnabledSatelliteFlag()) {
                repo.connectionState
            } else {

                flowOf(SatelliteConnectionState.Off)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SatelliteConnectionState.Off)

    /** 0-4 description of the connection strength */
    val signalStrength =
        if (Flags.oemEnabledSatelliteFlag()) {
                repo.signalStrength
            } else {
                flowOf(0)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), 0)

    val isDeviceProvisioned: Flow<Boolean> = deviceProvisioningInteractor.isDeviceProvisioned

    val isWifiActive: Flow<Boolean> =
        wifiInteractor.wifiNetwork.map { it is WifiNetworkModel.Active }

    private val allConnectionsOos =
        iconsInteractor.icons.aggregateOver(
            selector = { intr ->
                combine(intr.isInService, intr.isEmergencyOnly, intr.isNonTerrestrial) {
                    isInService,
                    isEmergencyOnly,
                    isNtn ->
                    !isInService && !isEmergencyOnly && !isNtn
                }
            },
            defaultValue = true, // no connections == everything is OOS
        ) { isOosAndNotEmergencyAndNotSatellite ->
            isOosAndNotEmergencyAndNotSatellite.all { it }
        }

    /** When all connections are considered OOS, satellite connectivity is potentially valid */
    val areAllConnectionsOutOfService =
        if (Flags.oemEnabledSatelliteFlag()) {
                combine(
                    allConnectionsOos,
                    iconsInteractor.isDeviceInEmergencyCallsOnlyMode,
                ) { connectionsOos, deviceEmergencyOnly ->
                    logBuffer.log(
                        TAG,
                        LogLevel.INFO,
                        {
                            bool1 = connectionsOos
                            bool2 = deviceEmergencyOnly
                        },
                        {
                            "Updating OOS status. allConnectionsOOs=$bool1 " +
                                "deviceEmergencyOnly=$bool2"
                        },
                    )
                    // If no connections exist, or all are OOS, then we look to the device-based
                    // service state to detect if any calls are possible
                    connectionsOos && !deviceEmergencyOnly
                }
            } else {
                flowOf(false)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), true)

    companion object {
        const val TAG = "DeviceBasedSatelliteInteractor"
    }
}

/**
 * aggregateOver allows us to combine over the leaf-nodes of successive lists emitted from the
 * top-level flow. Re-emits if the list changes, or any of the intermediate values change.
 *
 * Provides a way to connect the reactivity of the top-level flow with the reactivity of an
 * arbitrarily-defined relationship ([selector]) from R to the flow that R exposes.
 *
 * [defaultValue] allows for a default value to be used if there are no leaf nodes after applying
 * [selector]. E.g., if there are no mobile connections, assume that there is no service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private inline fun <R, reified S, T> Flow<List<R>>.aggregateOver(
    crossinline selector: (R) -> Flow<S>,
    defaultValue: T,
    crossinline transform: (Array<S>) -> T,
): Flow<T> {
    return map { list -> list.map { selector(it) } }
        .flatMapLatest { newFlows ->
            if (newFlows.isEmpty()) {
                flowOf(defaultValue)
            } else {
                combine(newFlows) { newVals -> transform(newVals) }
            }
        }
}
