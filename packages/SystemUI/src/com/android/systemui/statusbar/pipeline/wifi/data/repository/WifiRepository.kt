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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import kotlinx.coroutines.flow.StateFlow

/** Provides data related to the wifi state. */
interface WifiRepository {
    /** Observable for the current wifi enabled status. */
    val isWifiEnabled: StateFlow<Boolean>

    /** Observable for the current wifi default status. */
    val isWifiDefault: StateFlow<Boolean>

    /** Observable for the current primary wifi network. */
    val wifiNetwork: StateFlow<WifiNetworkModel>

    /**
     * Observable for secondary wifi networks (if any). Should specifically exclude the primary
     * network emitted by [wifiNetwork].
     *
     * This isn't used by phones/tablets, which only display the primary network, but may be used by
     * other variants like Car.
     */
    val secondaryNetworks: StateFlow<List<WifiNetworkModel>>

    /** Observable for the current wifi network activity. */
    val wifiActivity: StateFlow<DataActivityModel>

    /**
     * The list of known wifi networks, per [WifiManager.scanResults]. This list is passively
     * updated and does not trigger a scan.
     */
    val wifiScanResults: StateFlow<List<WifiScanEntry>>

    /**
     * Returns true if the device is currently connected to a wifi network with a valid SSID and
     * false otherwise.
     */
    fun isWifiConnectedWithValidSsid(): Boolean {
        val currentNetwork = wifiNetwork.value
        return currentNetwork is WifiNetworkModel.Active && currentNetwork.hasValidSsid()
    }

    companion object {
        /** Column name to use for [isWifiEnabled] for table logging. */
        const val COL_NAME_IS_ENABLED = "isEnabled"
        /** Column name to use for [isWifiDefault] for table logging. */
        const val COL_NAME_IS_DEFAULT = "isDefault"
    }
}

/**
 * A no-op interface used for Dagger bindings.
 *
 * [WifiRepositorySwitcher] needs to inject the "real" wifi repository, which could either be the
 * full [WifiRepositoryImpl] or just [DisabledWifiRepository]. Having this interface lets us bind
 * [RealWifiRepository], and then [WifiRepositorySwitcher] will automatically get the correct real
 * repository.
 */
interface RealWifiRepository : WifiRepository
