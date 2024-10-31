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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory
import com.android.systemui.statusbar.pipeline.dagger.WifiInputLog
import com.android.systemui.statusbar.pipeline.dagger.WifiTableLog
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toWifiDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.RealWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_ENABLED
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel.Unavailable.toHotspotDeviceType
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.android.wifitrackerlib.HotspotNetworkEntry
import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiPickerTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A real implementation of [WifiRepository] that uses [com.android.wifitrackerlib] as the source of
 * truth for wifi information.
 */
@SysUISingleton
class WifiRepositoryImpl
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val wifiPickerTrackerFactory: WifiPickerTrackerFactory,
    private val wifiManager: WifiManager,
    @WifiInputLog private val inputLogger: LogBuffer,
    @WifiTableLog private val tableLogger: TableLogBuffer,
) : RealWifiRepository, LifecycleOwner {

    override val lifecycle =
        LifecycleRegistry(this).also {
            mainExecutor.execute { it.currentState = Lifecycle.State.CREATED }
        }

    private var wifiPickerTracker: WifiPickerTracker? = null

    private val wifiPickerTrackerInfo: StateFlow<WifiPickerTrackerInfo> = run {
        var current =
            WifiPickerTrackerInfo(
                state = WIFI_STATE_DEFAULT,
                isDefault = false,
                primaryNetwork = WIFI_NETWORK_DEFAULT,
                secondaryNetworks = emptyList(),
            )
        callbackFlow {
                val callback =
                    object : WifiPickerTracker.WifiPickerTrackerCallback {
                        override fun onWifiEntriesChanged() {
                            val connectedEntry = wifiPickerTracker.mergedOrPrimaryConnection
                            logOnWifiEntriesChanged(connectedEntry)

                            val activeNetworks = wifiPickerTracker?.activeWifiEntries ?: emptyList()
                            val secondaryNetworks =
                                activeNetworks
                                    .filter { it != connectedEntry && !it.isPrimaryNetwork }
                                    .map { it.toWifiNetworkModel() }

                            // [WifiPickerTracker.connectedWifiEntry] will return the same instance
                            // but with updated internals. For example, when its validation status
                            // changes from false to true, the same instance is re-used but with the
                            // validated field updated.
                            //
                            // Because it's the same instance, the flow won't re-emit the value
                            // (even though the internals have changed). So, we need to transform it
                            // into our internal model immediately. [toWifiNetworkModel] always
                            // returns a new instance, so the flow is guaranteed to emit.
                            send(
                                newPrimaryNetwork =
                                    connectedEntry?.toPrimaryWifiNetworkModel()
                                        ?: WIFI_NETWORK_DEFAULT,
                                newSecondaryNetworks = secondaryNetworks,
                                newIsDefault = connectedEntry?.isDefaultNetwork ?: false,
                            )
                        }

                        override fun onWifiStateChanged() {
                            val state = wifiPickerTracker?.wifiState
                            logOnWifiStateChanged(state)
                            send(newState = state ?: WIFI_STATE_DEFAULT)
                        }

                        override fun onNumSavedNetworksChanged() {}

                        override fun onNumSavedSubscriptionsChanged() {}

                        private fun send(
                            newState: Int = current.state,
                            newIsDefault: Boolean = current.isDefault,
                            newPrimaryNetwork: WifiNetworkModel = current.primaryNetwork,
                            newSecondaryNetworks: List<WifiNetworkModel> =
                                current.secondaryNetworks,
                        ) {
                            val new =
                                WifiPickerTrackerInfo(
                                    newState,
                                    newIsDefault,
                                    newPrimaryNetwork,
                                    newSecondaryNetworks,
                                )
                            current = new
                            trySend(new)
                        }
                    }

                wifiPickerTracker =
                    wifiPickerTrackerFactory.create(lifecycle, callback, "WifiRepository").apply {
                        // By default, [WifiPickerTracker] will scan to see all available wifi
                        // networks in the area. Because SysUI only needs to display the
                        // **connected** network, we don't need scans to be running (and in fact,
                        // running scans is costly and should be avoided whenever possible).
                        this?.disableScanning()
                    }
                // The lifecycle must be STARTED in order for the callback to receive events.
                mainExecutor.execute { lifecycle.currentState = Lifecycle.State.STARTED }
                awaitClose {
                    mainExecutor.execute { lifecycle.currentState = Lifecycle.State.CREATED }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, current)
    }

    override val isWifiEnabled: StateFlow<Boolean> =
        wifiPickerTrackerInfo
            .map { it.state == WifiManager.WIFI_STATE_ENABLED }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                columnName = COL_NAME_IS_ENABLED,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val wifiNetwork: StateFlow<WifiNetworkModel> =
        wifiPickerTrackerInfo
            .map { it.primaryNetwork }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                initialValue = WIFI_NETWORK_DEFAULT,
            )
            .stateIn(scope, SharingStarted.Eagerly, WIFI_NETWORK_DEFAULT)

    override val secondaryNetworks: StateFlow<List<WifiNetworkModel>> =
        wifiPickerTrackerInfo
            .map { it.secondaryNetworks }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                columnName = "secondaryNetworks",
                initialValue = emptyList(),
            )
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * [WifiPickerTracker.getConnectedWifiEntry] stores a [MergedCarrierEntry] separately from the
     * [WifiEntry] for the primary connection. Therefore, we have to prefer the carrier-merged entry
     * if it exists, falling back on the connected entry if null
     */
    private val WifiPickerTracker?.mergedOrPrimaryConnection: WifiEntry?
        get() {
            val mergedEntry: MergedCarrierEntry? = this?.mergedCarrierEntry
            return if (mergedEntry != null && mergedEntry.isDefaultNetwork) {
                mergedEntry
            } else {
                this?.connectedWifiEntry
            }
        }

    /**
     * Converts WifiTrackerLib's [WifiEntry] into our internal model only if the entry is the
     * primary network. Returns an inactive network if it's not primary.
     */
    private fun WifiEntry.toPrimaryWifiNetworkModel(): WifiNetworkModel {
        return if (!this.isPrimaryNetwork) {
            WIFI_NETWORK_DEFAULT
        } else {
            this.toWifiNetworkModel()
        }
    }

    /** Converts WifiTrackerLib's [WifiEntry] into our internal model. */
    private fun WifiEntry.toWifiNetworkModel(): WifiNetworkModel {
        return if (this is MergedCarrierEntry) {
            this.convertCarrierMergedToModel()
        } else {
            this.convertNormalToModel()
        }
    }

    private fun MergedCarrierEntry.convertCarrierMergedToModel(): WifiNetworkModel {
        // WifiEntry instance values aren't guaranteed to be stable between method calls
        // because
        // WifiPickerTracker is continuously updating the same object. Save the level in a
        // local
        // variable so that checking the level validity here guarantees that the level will
        // still be
        // valid when we create the `WifiNetworkModel.Active` instance later. Otherwise, the
        // level
        // could be valid here but become invalid later, and `WifiNetworkModel.Active` will
        // throw
        // an exception. See b/362384551.

        return WifiNetworkModel.CarrierMerged.of(
            subscriptionId = this.subscriptionId,
            level = this.level,
            // WifiManager APIs to calculate the signal level start from 0, so
            // maxSignalLevel + 1 represents the total level buckets count.
            numberOfLevels = wifiManager.maxSignalLevel + 1,
        )
    }

    private fun WifiEntry.convertNormalToModel(): WifiNetworkModel {
        val hotspotDeviceType =
            if (this is HotspotNetworkEntry) {
                this.deviceType.toHotspotDeviceType()
            } else {
                WifiNetworkModel.HotspotDeviceType.NONE
            }

        return WifiNetworkModel.Active.of(
            isValidated = this.hasInternetAccess(),
            level = this.level,
            ssid = this.title,
            hotspotDeviceType = hotspotDeviceType,
        )
    }

    override val isWifiDefault: StateFlow<Boolean> =
        wifiPickerTrackerInfo
            .map { it.isDefault }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                columnName = COL_NAME_IS_DEFAULT,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val wifiActivity: StateFlow<DataActivityModel> =
        conflatedCallbackFlow {
                val callback =
                    WifiManager.TrafficStateCallback { state ->
                        logActivity(state)
                        trySend(state.toWifiDataActivityModel())
                    }
                wifiManager.registerTrafficStateCallback(mainExecutor, callback)
                awaitClose { wifiManager.unregisterTrafficStateCallback(callback) }
            }
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ACTIVITY_DEFAULT,
            )

    override val wifiScanResults: StateFlow<List<WifiScanEntry>> =
        conflatedCallbackFlow {
                val callback =
                    object : WifiManager.ScanResultsCallback() {
                        @SuppressLint("MissingPermission")
                        override fun onScanResultsAvailable() {
                            logScanResults()
                            trySend(wifiManager.scanResults.toModel())
                        }
                    }

                wifiManager.registerScanResultsCallback(bgDispatcher.asExecutor(), callback)

                awaitClose { wifiManager.unregisterScanResultsCallback(callback) }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private fun List<ScanResult>.toModel(): List<WifiScanEntry> = map { WifiScanEntry(it.SSID) }

    private fun logOnWifiEntriesChanged(connectedEntry: WifiEntry?) {
        inputLogger.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = connectedEntry.toString() },
            { "onWifiEntriesChanged. ConnectedEntry=$str1" },
        )
    }

    private fun logOnWifiStateChanged(state: Int?) {
        inputLogger.log(
            TAG,
            LogLevel.DEBUG,
            { int1 = state ?: -1 },
            { "onWifiStateChanged. State=${if (int1 == -1) null else int1}" },
        )
    }

    private fun logActivity(activity: Int) {
        inputLogger.log(
            TAG,
            LogLevel.DEBUG,
            { str1 = prettyPrintActivity(activity) },
            { "onActivityChanged: $str1" }
        )
    }

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

    private fun logScanResults() =
        inputLogger.log(TAG, LogLevel.DEBUG, {}, { "onScanResultsAvailable" })

    /**
     * Data class storing all the information fetched from [WifiPickerTracker].
     *
     * Used so that we only register a single callback on [WifiPickerTracker].
     */
    data class WifiPickerTrackerInfo(
        /** The current wifi state. See [WifiManager.getWifiState]. */
        val state: Int,
        /** True if wifi is currently the default connection and false otherwise. */
        val isDefault: Boolean,
        /** The currently primary wifi network. */
        val primaryNetwork: WifiNetworkModel,
        /** The current secondary network(s), if any. Specifically excludes the primary network. */
        val secondaryNetworks: List<WifiNetworkModel>
    )

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        @Application private val scope: CoroutineScope,
        @Main private val mainExecutor: Executor,
        @Background private val bgDispatcher: CoroutineDispatcher,
        private val wifiPickerTrackerFactory: WifiPickerTrackerFactory,
        @WifiInputLog private val inputLogger: LogBuffer,
        @WifiTableLog private val tableLogger: TableLogBuffer,
    ) {
        fun create(wifiManager: WifiManager): WifiRepositoryImpl {
            return WifiRepositoryImpl(
                scope,
                mainExecutor,
                bgDispatcher,
                wifiPickerTrackerFactory,
                wifiManager,
                inputLogger,
                tableLogger,
            )
        }
    }

    companion object {
        // Start out with no known wifi network.
        @VisibleForTesting val WIFI_NETWORK_DEFAULT = WifiNetworkModel.Inactive()

        private const val WIFI_STATE_DEFAULT = WifiManager.WIFI_STATE_DISABLED

        val ACTIVITY_DEFAULT = DataActivityModel(hasActivityIn = false, hasActivityOut = false)

        private const val TAG = "WifiTrackerLibInputLog"
    }
}
