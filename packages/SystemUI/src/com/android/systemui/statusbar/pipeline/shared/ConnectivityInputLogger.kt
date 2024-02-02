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

package com.android.systemui.statusbar.pipeline.shared

import android.net.Network
import android.net.NetworkCapabilities
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.SharedConnectivityInputLog
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import javax.inject.Inject

/** Logs for connectivity-related inputs that are shared across wifi, mobile, etc. */
@SysUISingleton
class ConnectivityInputLogger
@Inject
constructor(
    @SharedConnectivityInputLog private val buffer: LogBuffer,
) {
    fun logTuningChanged(tuningList: String?) {
        buffer.log(TAG, LogLevel.DEBUG, { str1 = tuningList }, { "onTuningChanged: $str1" })
    }

    fun logOnDefaultCapabilitiesChanged(
        network: Network,
        networkCapabilities: NetworkCapabilities,
    ) {
        LoggerHelper.logOnCapabilitiesChanged(
            buffer,
            TAG,
            network,
            networkCapabilities,
            isDefaultNetworkCallback = true,
        )
    }

    fun logOnDefaultLost(network: Network) {
        LoggerHelper.logOnLost(buffer, TAG, network, isDefaultNetworkCallback = true)
    }

    fun logDefaultConnectionsChanged(model: DefaultConnectionModel) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            model::messageInitializer,
            model::messagePrinter,
        )
    }

    fun logVcnSubscriptionId(subId: Int) {
        buffer.log(TAG, LogLevel.DEBUG, { int1 = subId }, { "vcnSubId changed: $int1" })
    }
}

private const val TAG = "ConnectivityInputLogger"
