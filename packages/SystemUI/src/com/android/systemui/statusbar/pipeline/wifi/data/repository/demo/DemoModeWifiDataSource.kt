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

import android.net.wifi.WifiManager
import android.os.Bundle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode.COMMAND_NETWORK
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Data source to map between demo mode commands and inputs into [DemoWifiRepository]'s flows */
@SysUISingleton
class DemoModeWifiDataSource
@Inject
constructor(
    demoModeController: DemoModeController,
    @Application scope: CoroutineScope,
) {
    private val demoCommandStream = demoModeController.demoFlowForCommand(COMMAND_NETWORK)
    private val _wifiCommands = demoCommandStream.map { args -> args.toWifiEvent() }
    val wifiEvents = _wifiCommands.shareIn(scope, SharingStarted.WhileSubscribed())

    private fun Bundle.toWifiEvent(): FakeWifiEventModel? {
        val wifi = getString("wifi") ?: return null
        return when (wifi) {
            "show" -> activeWifiEvent()
            "carriermerged" -> carrierMergedWifiEvent()
            else -> FakeWifiEventModel.WifiDisabled
        }
    }

    private fun Bundle.activeWifiEvent(): FakeWifiEventModel.Wifi {
        val level = getString("level")?.toInt()
        val activity = getString("activity")?.toActivity()
        val ssid = getString("ssid")
        val validated = getString("fully").toBoolean()

        return FakeWifiEventModel.Wifi(
            level = level,
            activity = activity,
            ssid = ssid,
            validated = validated,
        )
    }

    private fun Bundle.carrierMergedWifiEvent(): FakeWifiEventModel.CarrierMerged {
        val subId = getString("slot")?.toInt() ?: DEFAULT_CARRIER_MERGED_SUB_ID
        val level = getString("level")?.toInt() ?: 0
        val numberOfLevels = getString("numlevels")?.toInt() ?: DEFAULT_NUM_LEVELS

        return FakeWifiEventModel.CarrierMerged(subId, level, numberOfLevels)
    }

    private fun String.toActivity(): Int =
        when (this) {
            "inout" -> WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT
            "in" -> WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN
            "out" -> WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT
            else -> WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE
        }

    companion object {
        const val DEFAULT_CARRIER_MERGED_SUB_ID = 10
    }
}
