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

package com.android.systemui.statusbar.pipeline.mobile.data

import android.content.Intent
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.MobileInputLog
import javax.inject.Inject

/** Logs for inputs into the mobile pipeline. */
@SysUISingleton
class MobileInputLogger
@Inject
constructor(
    @MobileInputLog private val buffer: LogBuffer,
) {
    fun logOnServiceStateChanged(serviceState: ServiceState, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = serviceState.isEmergencyOnly
                bool2 = serviceState.roaming
                str1 = serviceState.operatorAlphaShort
            },
            {
                "onServiceStateChanged: subId=$int1 emergencyOnly=$bool1 roaming=$bool2" +
                    " operator=$str1"
            }
        )
    }

    fun logTopLevelServiceStateBroadcastEmergencyOnly(subId: Int, serviceState: ServiceState) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = serviceState.isEmergencyOnly
            },
            { "ACTION_SERVICE_STATE for subId=$int1. ServiceState.isEmergencyOnly=$bool1" }
        )
    }

    fun logTopLevelServiceStateBroadcastMissingExtras(subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { int1 = subId },
            { "ACTION_SERVICE_STATE for subId=$int1. Intent is missing extras. Ignoring" }
        )
    }

    fun logOnSignalStrengthsChanged(signalStrength: SignalStrength, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                str1 = signalStrength.toString()
            },
            { "onSignalStrengthsChanged: subId=$int1 strengths=$str1" }
        )
    }

    fun logOnDataConnectionStateChanged(dataState: Int, networkType: Int, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                int2 = dataState
                str1 = networkType.toString()
            },
            { "onDataConnectionStateChanged: subId=$int1 dataState=$int2 networkType=$str1" },
        )
    }

    fun logOnDataActivity(direction: Int, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                int2 = direction
            },
            { "onDataActivity: subId=$int1 direction=$int2" },
        )
    }

    fun logOnCarrierNetworkChange(active: Boolean, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = active
            },
            { "onCarrierNetworkChange: subId=$int1 active=$bool1" },
        )
    }

    fun logOnCarrierRoamingNtnModeChanged(active: Boolean) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { bool1 = active },
            { "onCarrierRoamingNtnModeChanged: $bool1" }
        )
    }

    fun logOnDisplayInfoChanged(displayInfo: TelephonyDisplayInfo, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                str1 = displayInfo.toString()
                bool1 = displayInfo.isRoaming
            },
            { "onDisplayInfoChanged: subId=$int1 displayInfo=$str1 isRoaming=$bool1" },
        )
    }

    fun logCarrierConfigChanged(subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { int1 = subId },
            { "onCarrierConfigChanged: subId=$int1" },
        )
    }

    fun logOnDataEnabledChanged(enabled: Boolean, subId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = enabled
            },
            { "onDataEnabledChanged: subId=$int1 enabled=$bool1" },
        )
    }

    fun logActionCarrierConfigChanged() {
        buffer.log(TAG, LogLevel.INFO, {}, { "Intent received: ACTION_CARRIER_CONFIG_CHANGED" })
    }

    fun logDefaultDataSubRatConfig(config: MobileMappings.Config) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = config.toString() },
            { "defaultDataSubRatConfig: $str1" }
        )
    }

    fun logDefaultMobileIconMapping(mapping: Map<String, SignalIcon.MobileIconGroup>) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = mapping.toString() },
            { "defaultMobileIconMapping: $str1" }
        )
    }

    fun logDefaultMobileIconGroup(group: SignalIcon.MobileIconGroup) {
        buffer.log(TAG, LogLevel.INFO, { str1 = group.name }, { "defaultMobileIconGroup: $str1" })
    }

    fun logOnSubscriptionsChanged() {
        buffer.log(TAG, LogLevel.INFO, {}, { "onSubscriptionsChanged" })
    }

    fun logServiceProvidersUpdatedBroadcast(intent: Intent) {
        val showSpn = intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_SPN, false)
        val spn = intent.getStringExtra(TelephonyManager.EXTRA_SPN)
        val dataSpn = intent.getStringExtra(TelephonyManager.EXTRA_DATA_SPN)
        val showPlmn = intent.getBooleanExtra(TelephonyManager.EXTRA_SHOW_PLMN, false)
        val plmn = intent.getStringExtra(TelephonyManager.EXTRA_PLMN)

        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                bool1 = showSpn
                str1 = spn
                str2 = dataSpn
                bool2 = showPlmn
                str3 = plmn
            },
            {
                "Intent: ACTION_SERVICE_PROVIDERS_UPDATED." +
                    " showSpn=$bool1 spn=$str1 dataSpn=$str2 showPlmn=$bool2 plmn=$str3"
            }
        )
    }

    fun logOnSimStateChanged() {
        buffer.log(TAG, LogLevel.INFO, "onSimStateChanged")
    }

    fun logPrioritizedNetworkAvailable(netId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { int1 = netId },
            { "Found prioritized network (nedId=$int1)" },
        )
    }

    fun logPrioritizedNetworkLost(netId: Int) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { int1 = netId },
            { "Lost prioritized network (nedId=$int1)" },
        )
    }
}

private const val TAG = "MobileInputLog"
