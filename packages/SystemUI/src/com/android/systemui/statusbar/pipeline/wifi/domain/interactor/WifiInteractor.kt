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

import android.net.wifi.WifiManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiActivityModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * The business logic layer for the wifi icon.
 *
 * This interactor processes information from our data layer into information that the UI layer can
 * use.
 */
@SysUISingleton
class WifiInteractor @Inject constructor(
    connectivityRepository: ConnectivityRepository,
    wifiRepository: WifiRepository,
) {
    /**
     * The SSID (service set identifier) of the wifi network. Null if we don't have a network, or
     * have a network but no valid SSID.
     */
    val ssid: Flow<String?> = wifiRepository.wifiNetwork.map { info ->
        when (info) {
            is WifiNetworkModel.Inactive -> null
            is WifiNetworkModel.CarrierMerged -> null
            is WifiNetworkModel.Active -> when {
                info.isPasspointAccessPoint || info.isOnlineSignUpForPasspointAccessPoint ->
                    info.passpointProviderFriendlyName
                info.ssid != WifiManager.UNKNOWN_SSID -> info.ssid
                else -> null
            }
        }
    }

    /** Our current enabled status. */
    val isEnabled: Flow<Boolean> = wifiRepository.isWifiEnabled

    /** Our current wifi network. See [WifiNetworkModel]. */
    val wifiNetwork: Flow<WifiNetworkModel> = wifiRepository.wifiNetwork

    /** Our current wifi activity. See [WifiActivityModel]. */
    val activity: StateFlow<WifiActivityModel> = wifiRepository.wifiActivity

    /** True if we're configured to force-hide the wifi icon and false otherwise. */
    val isForceHidden: Flow<Boolean> = connectivityRepository.forceHiddenSlots.map {
        it.contains(ConnectivitySlot.WIFI)
    }
}
