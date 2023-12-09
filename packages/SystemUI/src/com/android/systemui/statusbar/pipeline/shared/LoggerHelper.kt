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
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel

/** Helper object for logs that are shared between wifi and mobile. */
object LoggerHelper {
    fun logOnCapabilitiesChanged(
        buffer: LogBuffer,
        tag: String,
        network: Network,
        networkCapabilities: NetworkCapabilities,
        isDefaultNetworkCallback: Boolean,
    ) {
        buffer.log(
            tag,
            LogLevel.INFO,
            {
                bool1 = isDefaultNetworkCallback
                int1 = network.getNetId()
                str1 = networkCapabilities.toString()
            },
            { "on${if (bool1) "Default" else ""}CapabilitiesChanged: net=$int1 capabilities=$str1" }
        )
    }

    fun logOnLost(
        buffer: LogBuffer,
        tag: String,
        network: Network,
        isDefaultNetworkCallback: Boolean,
    ) {
        buffer.log(
            tag,
            LogLevel.INFO,
            {
                int1 = network.getNetId()
                bool1 = isDefaultNetworkCallback
            },
            { "on${if (bool1) "Default" else ""}Lost: net=$int1" }
        )
    }
}
