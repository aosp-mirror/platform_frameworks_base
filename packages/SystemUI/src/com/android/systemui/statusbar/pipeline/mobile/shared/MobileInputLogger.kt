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

package com.android.systemui.statusbar.pipeline.mobile.shared

import android.net.Network
import android.net.NetworkCapabilities
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyDisplayInfo
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.StatusBarConnectivityLog
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.statusbar.pipeline.mobile.shared.MobileInputLogger.Companion.toString
import com.android.systemui.statusbar.pipeline.shared.LoggerHelper
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

@SysUISingleton
class MobileInputLogger
@Inject
constructor(
    @StatusBarConnectivityLog private val buffer: LogBuffer,
) {
    /**
     * Logs a change in one of the **raw inputs** to the connectivity pipeline.
     *
     * Use this method for inputs that don't have any extra information besides their callback name.
     */
    fun logInputChange(callbackName: String) {
        buffer.log(SB_LOGGING_TAG, LogLevel.INFO, { str1 = callbackName }, { "Input: $str1" })
    }

    /** Logs a change in one of the **raw inputs** to the connectivity pipeline. */
    fun logInputChange(callbackName: String, changeInfo: String?) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                str1 = callbackName
                str2 = changeInfo
            },
            { "Input: $str1: $str2" }
        )
    }

    /** Logs a change in one of the **outputs** to the connectivity pipeline. */
    fun logOutputChange(outputParamName: String, changeInfo: String) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                str1 = outputParamName
                str2 = changeInfo
            },
            { "Output: $str1: $str2" }
        )
    }

    fun logOnCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
        isDefaultNetworkCallback: Boolean,
    ) {
        LoggerHelper.logOnCapabilitiesChanged(
            buffer,
            SB_LOGGING_TAG,
            network,
            networkCapabilities,
            isDefaultNetworkCallback,
        )
    }

    fun logOnLost(network: Network) {
        LoggerHelper.logOnLost(buffer, SB_LOGGING_TAG, network)
    }

    fun logOnServiceStateChanged(serviceState: ServiceState, subId: Int) {
        buffer.log(
            SB_LOGGING_TAG,
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

    fun logOnSignalStrengthsChanged(signalStrength: SignalStrength, subId: Int) {
        buffer.log(
            SB_LOGGING_TAG,
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
            SB_LOGGING_TAG,
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
            SB_LOGGING_TAG,
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
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = active
            },
            { "onCarrierNetworkChange: subId=$int1 active=$bool1" },
        )
    }

    fun logOnDisplayInfoChanged(displayInfo: TelephonyDisplayInfo, subId: Int) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                int1 = subId
                str1 = displayInfo.toString()
            },
            { "onDisplayInfoChanged: subId=$int1 displayInfo=$str1" },
        )
    }

    // TODO(b/238425913): We should split this class into mobile-specific and wifi-specific loggers.

    fun logUiAdapterSubIdsUpdated(subs: List<Int>) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            { str1 = subs.toString() },
            { "Sub IDs in MobileUiAdapter updated internally: $str1" },
        )
    }

    fun logUiAdapterSubIdsSentToIconController(subs: List<Int>) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            { str1 = subs.toString() },
            { "Sub IDs in MobileUiAdapter being sent to icon controller: $str1" },
        )
    }

    fun logCarrierConfigChanged(subId: Int) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            { int1 = subId },
            { "onCarrierConfigChanged: subId=$int1" },
        )
    }

    fun logOnDataEnabledChanged(enabled: Boolean, subId: Int) {
        buffer.log(
            SB_LOGGING_TAG,
            LogLevel.INFO,
            {
                int1 = subId
                bool1 = enabled
            },
            { "onDataEnabledChanged: subId=$int1 enabled=$bool1" },
        )
    }

    companion object {
        const val SB_LOGGING_TAG = "SbConnectivity"

        /** Log a change in one of the **inputs** to the connectivity pipeline. */
        fun Flow<Unit>.logInputChange(
            logger: MobileInputLogger,
            inputParamName: String,
        ): Flow<Unit> {
            return this.onEach { logger.logInputChange(inputParamName) }
        }

        /**
         * Log a change in one of the **inputs** to the connectivity pipeline.
         *
         * @param prettyPrint an optional function to transform the value into a readable string.
         * [toString] is used if no custom function is provided.
         */
        fun <T> Flow<T>.logInputChange(
            logger: MobileInputLogger,
            inputParamName: String,
            prettyPrint: (T) -> String = { it.toString() }
        ): Flow<T> {
            return this.onEach { logger.logInputChange(inputParamName, prettyPrint(it)) }
        }

        /**
         * Log a change in one of the **outputs** to the connectivity pipeline.
         *
         * @param prettyPrint an optional function to transform the value into a readable string.
         * [toString] is used if no custom function is provided.
         */
        fun <T> Flow<T>.logOutputChange(
            logger: MobileInputLogger,
            outputParamName: String,
            prettyPrint: (T) -> String = { it.toString() }
        ): Flow<T> {
            return this.onEach { logger.logOutputChange(outputParamName, prettyPrint(it)) }
        }
    }
}
