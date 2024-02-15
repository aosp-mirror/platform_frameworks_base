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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.demo

import android.os.Bundle
import android.telephony.Annotation.DataActivityType
import android.telephony.TelephonyManager.DATA_ACTIVITY_IN
import android.telephony.TelephonyManager.DATA_ACTIVITY_INOUT
import android.telephony.TelephonyManager.DATA_ACTIVITY_NONE
import android.telephony.TelephonyManager.DATA_ACTIVITY_OUT
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.demomode.DemoMode.COMMAND_NETWORK
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.Mobile
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.MobileDisabled
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/**
 * Data source that can map from demo mode commands to inputs into the
 * [DemoMobileConnectionsRepository]'s flows
 */
@SysUISingleton
class DemoModeMobileConnectionDataSource
@Inject
constructor(
    demoModeController: DemoModeController,
    @Application scope: CoroutineScope,
) {
    private val demoCommandStream = demoModeController.demoFlowForCommand(COMMAND_NETWORK)

    // If the args contains "mobile", then all of the args are relevant. It's just the way demo mode
    // commands work and it's a little silly
    private val _mobileCommands = demoCommandStream.map { args -> args.toMobileEvent() }
    val mobileEvents = _mobileCommands.shareIn(scope, SharingStarted.WhileSubscribed())

    private fun Bundle.toMobileEvent(): FakeNetworkEventModel? {
        val mobile = getString("mobile") ?: return null
        return if (mobile == "show") {
            activeMobileEvent()
        } else {
            MobileDisabled(subId = getString("slot")?.toInt())
        }
    }

    /** Parse a valid mobile command string into a network event */
    private fun Bundle.activeMobileEvent(): Mobile {
        // There are many key/value pairs supported by mobile demo mode. Bear with me here
        val level = getString("level")?.toInt()
        val dataType = getString("datatype")?.toDataType()
        val slot = getString("slot")?.toInt()
        val carrierId = getString("carrierid")?.toInt()
        val inflateStrength = getString("inflate")?.toBoolean()
        val activity = getString("activity")?.toActivity()
        val carrierNetworkChange = getString("carriernetworkchange") == "show"
        val roaming = getString("roam") == "show"
        val name = getString("networkname") ?: "demo mode"
        val slice = getString("slice").toBoolean()
        val ntn = getString("ntn").toBoolean()

        return Mobile(
            level = level,
            dataType = dataType,
            subId = slot,
            carrierId = carrierId,
            inflateStrength = inflateStrength,
            activity = activity,
            carrierNetworkChange = carrierNetworkChange,
            roaming = roaming,
            name = name,
            slice = slice,
            ntn = ntn,
        )
    }
}

private fun String.toDataType(): MobileIconGroup =
    when (this) {
        "1x" -> TelephonyIcons.ONE_X
        "3g" -> TelephonyIcons.THREE_G
        "4g" -> TelephonyIcons.FOUR_G
        "4g+" -> TelephonyIcons.FOUR_G_PLUS
        "5g" -> TelephonyIcons.NR_5G
        "5ge" -> TelephonyIcons.LTE_CA_5G_E
        "5g+" -> TelephonyIcons.NR_5G_PLUS
        "e" -> TelephonyIcons.E
        "g" -> TelephonyIcons.G
        "h" -> TelephonyIcons.H
        "h+" -> TelephonyIcons.H_PLUS
        "lte" -> TelephonyIcons.LTE
        "lte+" -> TelephonyIcons.LTE_PLUS
        "dis" -> TelephonyIcons.DATA_DISABLED
        "not" -> TelephonyIcons.NOT_DEFAULT_DATA
        else -> TelephonyIcons.UNKNOWN
    }

@DataActivityType
private fun String.toActivity(): Int =
    when (this) {
        "inout" -> DATA_ACTIVITY_INOUT
        "in" -> DATA_ACTIVITY_IN
        "out" -> DATA_ACTIVITY_OUT
        else -> DATA_ACTIVITY_NONE
    }
