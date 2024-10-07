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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.demo

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toWifiDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Demo-able wifi repository to support SystemUI demo mode commands. */
class DemoWifiRepository
@Inject
constructor(
    private val dataSource: DemoModeWifiDataSource,
    @Application private val scope: CoroutineScope,
) : WifiRepository {
    private var demoCommandJob: Job? = null

    private val _isWifiEnabled = MutableStateFlow(false)
    override val isWifiEnabled: StateFlow<Boolean> = _isWifiEnabled

    private val _isWifiDefault = MutableStateFlow(false)
    override val isWifiDefault: StateFlow<Boolean> = _isWifiDefault

    private val _wifiNetwork = MutableStateFlow<WifiNetworkModel>(WifiNetworkModel.Inactive())
    override val wifiNetwork: StateFlow<WifiNetworkModel> = _wifiNetwork

    private val _secondaryNetworks = MutableStateFlow<List<WifiNetworkModel>>(emptyList())
    override val secondaryNetworks: StateFlow<List<WifiNetworkModel>> = _secondaryNetworks

    private val _wifiActivity =
        MutableStateFlow(DataActivityModel(hasActivityIn = false, hasActivityOut = false))
    override val wifiActivity: StateFlow<DataActivityModel> = _wifiActivity

    private val _wifiScanResults: MutableStateFlow<List<WifiScanEntry>> =
        MutableStateFlow(emptyList())
    override val wifiScanResults: StateFlow<List<WifiScanEntry>> = _wifiScanResults

    fun startProcessingCommands() {
        demoCommandJob =
            scope.launch {
                dataSource.wifiEvents.filterNotNull().collect { event -> processEvent(event) }
            }
    }

    fun stopProcessingCommands() {
        demoCommandJob?.cancel()
    }

    private fun processEvent(event: FakeWifiEventModel) =
        when (event) {
            is FakeWifiEventModel.Wifi -> processEnabledWifiState(event)
            is FakeWifiEventModel.CarrierMerged -> processCarrierMergedWifiState(event)
            is FakeWifiEventModel.WifiDisabled -> processDisabledWifiState()
        }

    private fun processDisabledWifiState() {
        _isWifiEnabled.value = false
        _isWifiDefault.value = false
        _wifiActivity.value = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
        _wifiNetwork.value = WifiNetworkModel.Inactive()
    }

    private fun processEnabledWifiState(event: FakeWifiEventModel.Wifi) {
        _isWifiEnabled.value = true
        _isWifiDefault.value = true
        _wifiActivity.value = event.activity.toWifiDataActivityModel()
        _wifiNetwork.value = event.toWifiNetworkModel()
    }

    private fun processCarrierMergedWifiState(event: FakeWifiEventModel.CarrierMerged) {
        _isWifiEnabled.value = true
        _isWifiDefault.value = true
        _wifiActivity.value = event.activity.toWifiDataActivityModel()
        _wifiNetwork.value = event.toCarrierMergedModel()
    }

    private fun FakeWifiEventModel.Wifi.toWifiNetworkModel(): WifiNetworkModel =
        WifiNetworkModel.Active.of(
            isValidated = validated ?: true,
            level = level ?: 0,
            ssid = ssid ?: DEMO_NET_SSID,
            hotspotDeviceType = hotspotDeviceType,
        )

    private fun FakeWifiEventModel.CarrierMerged.toCarrierMergedModel(): WifiNetworkModel =
        WifiNetworkModel.CarrierMerged.of(
            subscriptionId = subscriptionId,
            level = level,
            numberOfLevels = numberOfLevels,
        )

    companion object {
        private const val DEMO_NET_SSID = "Demo SSID"
    }
}
