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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.prod

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toWifiDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Object to provide shared helper functions between [WifiRepositoryImpl] and
 * [WifiRepositoryViaTrackerLib].
 */
object WifiRepositoryHelper {
    /** Creates a flow that fetches the [DataActivityModel] from [WifiManager]. */
    fun createActivityFlow(
        wifiManager: WifiManager,
        @Main mainExecutor: Executor,
        scope: CoroutineScope,
        tableLogBuffer: TableLogBuffer,
        inputLogger: (String) -> Unit,
    ): StateFlow<DataActivityModel> {
        return conflatedCallbackFlow {
                val callback =
                    WifiManager.TrafficStateCallback { state ->
                        inputLogger.invoke(prettyPrintActivity(state))
                        trySend(state.toWifiDataActivityModel())
                    }
                wifiManager.registerTrafficStateCallback(mainExecutor, callback)
                awaitClose { wifiManager.unregisterTrafficStateCallback(callback) }
            }
            .logDiffsForTable(
                tableLogBuffer,
                columnPrefix = ACTIVITY_PREFIX,
                initialValue = ACTIVITY_DEFAULT,
            )
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ACTIVITY_DEFAULT,
            )
    }

    /**
     * Creates a flow that listens for new [ScanResult]s from [WifiManager]. Does not request a scan
     */
    fun createNetworkScanFlow(
        wifiManager: WifiManager,
        scope: CoroutineScope,
        @Background dispatcher: CoroutineDispatcher,
        inputLogger: () -> Unit,
    ): StateFlow<List<WifiScanEntry>> {
        return conflatedCallbackFlow {
                val callback =
                    object : WifiManager.ScanResultsCallback() {
                        @SuppressLint("MissingPermission")
                        override fun onScanResultsAvailable() {
                            inputLogger.invoke()
                            trySend(wifiManager.scanResults.toModel())
                        }
                    }

                wifiManager.registerScanResultsCallback(dispatcher.asExecutor(), callback)

                awaitClose { wifiManager.unregisterScanResultsCallback(callback) }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    private fun List<ScanResult>.toModel(): List<WifiScanEntry> = map { WifiScanEntry(it.SSID) }

    // TODO(b/292534484): This print should only be done in [MessagePrinter] part of the log buffer.
    private fun prettyPrintActivity(activity: Int): String {
        return when (activity) {
            WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE -> "NONE"
            WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN -> "IN"
            WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT -> "OUT"
            WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT -> "INOUT"
            else -> "INVALID"
        }
    }

    private const val ACTIVITY_PREFIX = "wifiActivity"
    val ACTIVITY_DEFAULT = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
}
