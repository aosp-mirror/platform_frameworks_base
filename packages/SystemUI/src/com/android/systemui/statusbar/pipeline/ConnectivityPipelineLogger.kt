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

package com.android.systemui.statusbar.pipeline

import android.net.Network
import android.net.NetworkCapabilities
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.StatusBarConnectivityLog
import javax.inject.Inject

@SysUISingleton
class ConnectivityPipelineLogger @Inject constructor(
    @StatusBarConnectivityLog private val buffer: LogBuffer,
) {
    fun logOnCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = network.getNetId()
                str1 = networkCapabilities.toString()
            },
            {
                "onCapabilitiesChanged: net=$int1 capabilities=$str1"
            }
        )
    }

    fun logOnLost(network: Network) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                int1 = network.getNetId()
            },
            {
                "onLost: net=$int1"
            }
        )
    }
}

private const val TAG = "SbConnectivityPipeline"
