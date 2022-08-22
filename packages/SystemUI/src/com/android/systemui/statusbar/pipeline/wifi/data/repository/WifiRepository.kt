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

import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import android.util.Log
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.SB_LOGGING_TAG
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Provides data related to the wifi state.
 */
interface WifiRepository {
    /**
     * Observable for the current state of wifi; `null` when there is no active wifi.
     */
    val wifiModel: Flow<WifiModel?>

    /**
     * Observable for the current wifi network activity.
     */
    val wifiActivity: Flow<WifiActivityModel>
}

/** Real implementation of [WifiRepository]. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class WifiRepositoryImpl @Inject constructor(
        wifiManager: WifiManager?,
        @Main mainExecutor: Executor,
        logger: ConnectivityPipelineLogger,
) : WifiRepository {

    // TODO(b/238425913): Actually implement the wifiModel flow.
    override val wifiModel: Flow<WifiModel?> = flowOf(WifiModel(ssid = "AB"))

    override val wifiActivity: Flow<WifiActivityModel> =
            if (wifiManager == null) {
                Log.w(SB_LOGGING_TAG, "Null WifiManager; skipping activity callback")
                flowOf(ACTIVITY_DEFAULT)
            } else {
                conflatedCallbackFlow {
                    val callback = TrafficStateCallback { state ->
                        logger.logInputChange("onTrafficStateChange", prettyPrintActivity(state))
                        trySend(trafficStateToWifiActivityModel(state))
                    }

                    wifiManager.registerTrafficStateCallback(mainExecutor, callback)
                    trySend(ACTIVITY_DEFAULT)

                    awaitClose { wifiManager.unregisterTrafficStateCallback(callback) }
                }
            }

    companion object {
        val ACTIVITY_DEFAULT = WifiActivityModel(hasActivityIn = false, hasActivityOut = false)

        private fun trafficStateToWifiActivityModel(state: Int): WifiActivityModel {
            return WifiActivityModel(
                hasActivityIn = state == TrafficStateCallback.DATA_ACTIVITY_IN ||
                    state == TrafficStateCallback.DATA_ACTIVITY_INOUT,
                hasActivityOut = state == TrafficStateCallback.DATA_ACTIVITY_OUT ||
                    state == TrafficStateCallback.DATA_ACTIVITY_INOUT,
            )
        }

        private fun prettyPrintActivity(activity: Int): String {
            return when (activity) {
                TrafficStateCallback.DATA_ACTIVITY_NONE -> "NONE"
                TrafficStateCallback.DATA_ACTIVITY_IN -> "IN"
                TrafficStateCallback.DATA_ACTIVITY_OUT -> "OUT"
                TrafficStateCallback.DATA_ACTIVITY_INOUT -> "INOUT"
                else -> "INVALID"
            }
        }
    }
}
