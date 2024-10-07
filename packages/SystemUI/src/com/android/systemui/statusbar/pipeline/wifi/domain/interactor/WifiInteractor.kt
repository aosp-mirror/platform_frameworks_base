/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.domain.interactor

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The business logic layer for the wifi icon.
 *
 * This interactor processes information from our data layer into information that the UI layer can
 * use.
 */
interface WifiInteractor {
    /**
     * The SSID (service set identifier) of the wifi network. Null if we don't have a network, or
     * have a network but no valid SSID.
     */
    val ssid: Flow<String?>

    /** Our current enabled status. */
    val isEnabled: Flow<Boolean>

    /** Our current default status. */
    val isDefault: Flow<Boolean>

    /** Our current wifi network. See [WifiNetworkModel]. */
    val wifiNetwork: Flow<WifiNetworkModel>

    /** Our current wifi activity. See [DataActivityModel]. */
    val activity: StateFlow<DataActivityModel>

    /** True if we're configured to force-hide the wifi icon and false otherwise. */
    val isForceHidden: Flow<Boolean>

    /** True if there are networks available other than the currently-connected one */
    val areNetworksAvailable: StateFlow<Boolean>
}

@SysUISingleton
class WifiInteractorImpl
@Inject
constructor(
    connectivityRepository: ConnectivityRepository,
    wifiRepository: WifiRepository,
    @Application scope: CoroutineScope,
) : WifiInteractor {

    override val ssid: Flow<String?> =
        wifiRepository.wifiNetwork.map { info ->
            when (info) {
                is WifiNetworkModel.Unavailable -> null
                is WifiNetworkModel.Invalid -> null
                is WifiNetworkModel.Inactive -> null
                is WifiNetworkModel.CarrierMerged -> null
                is WifiNetworkModel.Active ->
                    when {
                        info.hasValidSsid() -> info.ssid
                        else -> null
                    }
            }
        }

    override val isEnabled: Flow<Boolean> = wifiRepository.isWifiEnabled

    override val isDefault: Flow<Boolean> = wifiRepository.isWifiDefault

    override val wifiNetwork: Flow<WifiNetworkModel> = wifiRepository.wifiNetwork

    override val activity: StateFlow<DataActivityModel> = wifiRepository.wifiActivity

    override val isForceHidden: Flow<Boolean> =
        connectivityRepository.forceHiddenSlots.map { it.contains(ConnectivitySlot.WIFI) }

    override val areNetworksAvailable: StateFlow<Boolean> =
        combine(
                wifiNetwork,
                wifiRepository.wifiScanResults,
            ) { currentNetwork, scanResults ->
                // We consider networks to be available if the scan results list contains networks
                // other than the one that is currently connected
                if (scanResults.isEmpty()) {
                    false
                } else if (currentNetwork !is WifiNetworkModel.Active) {
                    true
                } else {
                    anyNonMatchingNetworkExists(currentNetwork, scanResults)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    private fun anyNonMatchingNetworkExists(
        currentNetwork: WifiNetworkModel.Active,
        availableNetworks: List<WifiScanEntry>
    ): Boolean = availableNetworks.firstOrNull { it.ssid != currentNetwork.ssid } != null
}
