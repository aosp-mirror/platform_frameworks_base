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

import android.net.wifi.WifiManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory
import com.android.systemui.statusbar.pipeline.dagger.WifiTrackerLibInputLog
import com.android.systemui.statusbar.pipeline.dagger.WifiTrackerLibTableLog
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_ENABLED
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryViaTrackerLibDagger
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_STATE_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiPickerTracker
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * An implementation of [WifiRepository] that uses [com.android.wifitrackerlib] as the source of
 * truth for wifi information.
 *
 * Serves as a possible replacement for [WifiRepositoryImpl]. See b/292534484.
 */
@SysUISingleton
class WifiRepositoryViaTrackerLib
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    @Main private val mainExecutor: Executor,
    private val wifiPickerTrackerFactory: WifiPickerTrackerFactory,
    private val wifiManager: WifiManager,
    @WifiTrackerLibInputLog private val inputLogger: LogBuffer,
    @WifiTrackerLibTableLog private val wifiTrackerLibTableLogBuffer: TableLogBuffer,
) : WifiRepositoryViaTrackerLibDagger, LifecycleOwner {

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
                network = WIFI_NETWORK_DEFAULT,
            )
        callbackFlow {
                val callback =
                    object : WifiPickerTracker.WifiPickerTrackerCallback {
                        override fun onWifiEntriesChanged() {
                            val connectedEntry = wifiPickerTracker?.connectedWifiEntry
                            logOnWifiEntriesChanged(connectedEntry)

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
                                newNetwork = connectedEntry?.toWifiNetworkModel()
                                        ?: WIFI_NETWORK_DEFAULT,
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
                            newNetwork: WifiNetworkModel = current.network,
                        ) {
                            val new = WifiPickerTrackerInfo(newState, newIsDefault, newNetwork)
                            current = new
                            trySend(new)
                        }
                    }

                // TODO(b/292591403): [WifiPickerTrackerFactory] currently scans to see all
                // available wifi networks every 10s. Because SysUI only needs to display the
                // **connected** network, we don't need scans to be running. We should disable these
                // scans (ideal) or at least run them very infrequently.
                wifiPickerTracker = wifiPickerTrackerFactory.create(lifecycle, callback)
                // The lifecycle must be STARTED in order for the callback to receive events.
                mainExecutor.execute { lifecycle.currentState = Lifecycle.State.STARTED }
                awaitClose {
                    mainExecutor.execute { lifecycle.currentState = Lifecycle.State.CREATED }
                }
            }
            // TODO(b/292534484): Update to Eagerly once scans are disabled. (Here and other flows)
            .stateIn(scope, SharingStarted.WhileSubscribed(), current)
    }

    override val isWifiEnabled: StateFlow<Boolean> =
        wifiPickerTrackerInfo
            .map { it.state == WifiManager.WIFI_STATE_ENABLED }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTrackerLibTableLogBuffer,
                columnPrefix = "",
                columnName = COL_NAME_IS_ENABLED,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val wifiNetwork: StateFlow<WifiNetworkModel> =
        wifiPickerTrackerInfo
            .map { it.network }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTrackerLibTableLogBuffer,
                columnPrefix = "",
                initialValue = WIFI_NETWORK_DEFAULT,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), WIFI_NETWORK_DEFAULT)

    /** Converts WifiTrackerLib's [WifiEntry] into our internal model. */
    private fun WifiEntry.toWifiNetworkModel(): WifiNetworkModel {
        if (!this.isPrimaryNetwork) {
            return WIFI_NETWORK_DEFAULT
        }
        return if (this is MergedCarrierEntry) {
            WifiNetworkModel.CarrierMerged(
                networkId = NETWORK_ID,
                // TODO(b/292534484): Fetch the real subscription ID from [MergedCarrierEntry].
                subscriptionId = TEMP_SUB_ID,
                level = this.level,
                // WifiManager APIs to calculate the signal level start from 0, so
                // maxSignalLevel + 1 represents the total level buckets count.
                numberOfLevels = wifiManager.maxSignalLevel + 1,
            )
        } else {
            WifiNetworkModel.Active(
                networkId = NETWORK_ID,
                isValidated = this.hasInternetAccess(),
                level = this.level,
                ssid = this.title,
                // With WifiTrackerLib, [WifiEntry.title] will appropriately fetch the  SSID for
                // typical wifi networks *and* passpoint/OSU APs. So, the AP-specific values can
                // always be false/null in this repository.
                // TODO(b/292534484): Remove these fields from the wifi network model once this
                //  repository is fully enabled.
                isPasspointAccessPoint = false,
                isOnlineSignUpForPasspointAccessPoint = false,
                passpointProviderFriendlyName = null,
            )
        }
    }

    override val isWifiDefault: StateFlow<Boolean> =
        wifiPickerTrackerInfo
            .map { it.isDefault }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTrackerLibTableLogBuffer,
                columnPrefix = "",
                columnName = COL_NAME_IS_DEFAULT,
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val wifiActivity: StateFlow<DataActivityModel> =
        WifiRepositoryHelper.createActivityFlow(
            wifiManager,
            mainExecutor,
            scope,
            wifiTrackerLibTableLogBuffer,
            this::logActivity,
        )

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

    private fun logActivity(activity: String) {
        inputLogger.log(TAG, LogLevel.DEBUG, { str1 = activity }, { "onActivityChanged: $str1" })
    }

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
        val network: WifiNetworkModel,
    )

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        @Application private val scope: CoroutineScope,
        @Main private val mainExecutor: Executor,
        private val wifiPickerTrackerFactory: WifiPickerTrackerFactory,
        @WifiTrackerLibInputLog private val inputLogger: LogBuffer,
        @WifiTrackerLibTableLog private val wifiTrackerLibTableLogBuffer: TableLogBuffer,
    ) {
        fun create(wifiManager: WifiManager): WifiRepositoryViaTrackerLib {
            return WifiRepositoryViaTrackerLib(
                scope,
                mainExecutor,
                wifiPickerTrackerFactory,
                wifiManager,
                inputLogger,
                wifiTrackerLibTableLogBuffer,
            )
        }
    }

    companion object {
        private const val TAG = "WifiTrackerLibInputLog"

        /**
         * [WifiNetworkModel.Active.networkId] is only used at the repository layer. It's used by
         * [WifiRepositoryImpl], which tracks the ID in order to correctly apply the framework
         * callbacks within the repository.
         *
         * Since this class does not need to manually apply framework callbacks and since the
         * network ID is not used beyond the repository, it's safe to use an invalid ID in this
         * repository.
         *
         * The [WifiNetworkModel.Active.networkId] field should be deleted once we've fully migrated
         * to [WifiRepositoryViaTrackerLib].
         */
        private const val NETWORK_ID = -1

        /**
         * A temporary subscription ID until WifiTrackerLib exposes a method to fetch the
         * subscription ID.
         *
         * Use -2 because [SubscriptionManager.INVALID_SUBSCRIPTION_ID] is -1.
         */
        private const val TEMP_SUB_ID = -2
    }
}
