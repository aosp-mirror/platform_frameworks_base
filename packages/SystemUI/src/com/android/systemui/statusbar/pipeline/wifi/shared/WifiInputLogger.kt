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

package com.android.systemui.statusbar.pipeline.wifi.shared

import android.net.Network
import android.net.NetworkCapabilities
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.plugins.log.LogBuffer
import com.android.systemui.plugins.log.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.WifiInputLog
import com.android.systemui.statusbar.pipeline.shared.LoggerHelper
import javax.inject.Inject

/**
 * Logger for all the wifi-related inputs (intents, callbacks, etc.) that the wifi repo receives.
 */
@SysUISingleton
class WifiInputLogger
@Inject
constructor(
    @WifiInputLog val buffer: LogBuffer,
) {
    fun logOnCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
        isDefaultNetworkCallback: Boolean,
    ) {
        LoggerHelper.logOnCapabilitiesChanged(
            buffer,
            TAG,
            network,
            networkCapabilities,
            isDefaultNetworkCallback,
        )
    }

    fun logOnLost(network: Network, isDefaultNetworkCallback: Boolean) {
        LoggerHelper.logOnLost(buffer, TAG, network, isDefaultNetworkCallback)
    }

    fun logIntent(intentName: String) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = intentName }, { "Intent received: $str1" })
    }

    fun logActivity(activity: String) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = activity }, { "Activity: $str1" })
    }
}

private const val TAG = "WifiInputLog"
