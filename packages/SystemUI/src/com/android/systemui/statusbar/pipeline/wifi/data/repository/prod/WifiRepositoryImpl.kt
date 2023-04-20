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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.prod

import android.annotation.SuppressLint
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.dagger.WifiTableLog
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logInputChange
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toWifiDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.RealWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/** Real implementation of [WifiRepository]. */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
@SuppressLint("MissingPermission")
class WifiRepositoryImpl
@Inject
constructor(
    broadcastDispatcher: BroadcastDispatcher,
    connectivityManager: ConnectivityManager,
    logger: ConnectivityPipelineLogger,
    @WifiTableLog wifiTableLogBuffer: TableLogBuffer,
    @Main mainExecutor: Executor,
    @Application scope: CoroutineScope,
    wifiManager: WifiManager,
) : RealWifiRepository {

    private val wifiStateChangeEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
            .logInputChange(logger, "WIFI_STATE_CHANGED_ACTION intent")

    private val wifiNetworkChangeEvents: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    // Because [WifiManager] doesn't expose a wifi enabled change listener, we do it
    // internally by fetching [WifiManager.isWifiEnabled] whenever we think the state may
    // have changed.
    override val isWifiEnabled: StateFlow<Boolean> =
        merge(wifiNetworkChangeEvents, wifiStateChangeEvents)
            .mapLatest { wifiManager.isWifiEnabled }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "",
                columnName = "isWifiEnabled",
                initialValue = wifiManager.isWifiEnabled,
            )
            .stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = wifiManager.isWifiEnabled,
            )

    override val isWifiDefault: StateFlow<Boolean> =
        conflatedCallbackFlow {
                // Note: This callback doesn't do any logging because we already log every network
                // change in the [wifiNetwork] callback.
                val callback =
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities
                        ) {
                            // This method will always be called immediately after the network
                            // becomes the default, in addition to any time the capabilities change
                            // while the network is the default.
                            // If this network contains valid wifi info, then wifi is the default
                            // network.
                            val wifiInfo = networkCapabilitiesToWifiInfo(networkCapabilities)
                            trySend(wifiInfo != null)
                        }

                        override fun onLost(network: Network) {
                            // The system no longer has a default network, so wifi is definitely not
                            // default.
                            trySend(false)
                        }
                    }

                connectivityManager.registerDefaultNetworkCallback(callback)
                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "",
                columnName = "isWifiDefault",
                initialValue = false,
            )
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), initialValue = false)

    override val wifiNetwork: StateFlow<WifiNetworkModel> =
        conflatedCallbackFlow {
                var currentWifi: WifiNetworkModel = WIFI_NETWORK_DEFAULT

                val callback =
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities
                        ) {
                            logger.logOnCapabilitiesChanged(network, networkCapabilities)

                            wifiNetworkChangeEvents.tryEmit(Unit)

                            val wifiInfo = networkCapabilitiesToWifiInfo(networkCapabilities)
                            if (wifiInfo?.isPrimary == true) {
                                val wifiNetworkModel =
                                    createWifiNetworkModel(
                                        wifiInfo,
                                        network,
                                        networkCapabilities,
                                        wifiManager,
                                    )
                                logger.logTransformation(
                                    WIFI_NETWORK_CALLBACK_NAME,
                                    oldValue = currentWifi,
                                    newValue = wifiNetworkModel,
                                )
                                currentWifi = wifiNetworkModel
                                trySend(wifiNetworkModel)
                            }
                        }

                        override fun onLost(network: Network) {
                            logger.logOnLost(network)

                            wifiNetworkChangeEvents.tryEmit(Unit)

                            val wifi = currentWifi
                            if (
                                wifi is WifiNetworkModel.Active &&
                                    wifi.networkId == network.getNetId()
                            ) {
                                val newNetworkModel = WifiNetworkModel.Inactive
                                logger.logTransformation(
                                    WIFI_NETWORK_CALLBACK_NAME,
                                    oldValue = wifi,
                                    newValue = newNetworkModel,
                                )
                                currentWifi = newNetworkModel
                                trySend(newNetworkModel)
                            }
                        }
                    }

                connectivityManager.registerNetworkCallback(WIFI_NETWORK_CALLBACK_REQUEST, callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "wifiNetwork",
                initialValue = WIFI_NETWORK_DEFAULT,
            )
            // There will be multiple wifi icons in different places that will frequently
            // subscribe/unsubscribe to flows as the views attach/detach. Using [stateIn] ensures
            // that new subscribes will get the latest value immediately upon subscription.
            // Otherwise, the views could show stale data. See b/244173280.
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = WIFI_NETWORK_DEFAULT,
            )

    override val wifiActivity: StateFlow<DataActivityModel> =
        conflatedCallbackFlow {
                val callback = TrafficStateCallback { state ->
                    logger.logInputChange("onTrafficStateChange", prettyPrintActivity(state))
                    trySend(state.toWifiDataActivityModel())
                }
                wifiManager.registerTrafficStateCallback(mainExecutor, callback)
                awaitClose { wifiManager.unregisterTrafficStateCallback(callback) }
            }
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = ACTIVITY_PREFIX,
                initialValue = ACTIVITY_DEFAULT,
            )
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = ACTIVITY_DEFAULT,
            )

    companion object {
        private const val ACTIVITY_PREFIX = "wifiActivity"

        val ACTIVITY_DEFAULT = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
        // Start out with no known wifi network.
        // Note: [WifiStatusTracker] (the old implementation of connectivity logic) does do an
        // initial fetch to get a starting wifi network. But, it uses a deprecated API
        // [WifiManager.getConnectionInfo()], and the deprecation doc indicates to just use
        // [ConnectivityManager.NetworkCallback] results instead. So, for now we'll just rely on the
        // NetworkCallback inside [wifiNetwork] for our wifi network information.
        val WIFI_NETWORK_DEFAULT = WifiNetworkModel.Inactive

        private fun networkCapabilitiesToWifiInfo(
            networkCapabilities: NetworkCapabilities
        ): WifiInfo? {
            return when {
                networkCapabilities.hasTransport(TRANSPORT_WIFI) ->
                    networkCapabilities.transportInfo as WifiInfo?
                networkCapabilities.hasTransport(TRANSPORT_CELLULAR) ->
                    // Sometimes, cellular networks can act as wifi networks (known as VCN --
                    // virtual carrier network). So, see if this cellular network has wifi info.
                    Utils.tryGetWifiInfoForVcn(networkCapabilities)
                else -> null
            }
        }

        private fun createWifiNetworkModel(
            wifiInfo: WifiInfo,
            network: Network,
            networkCapabilities: NetworkCapabilities,
            wifiManager: WifiManager,
        ): WifiNetworkModel {
            return if (wifiInfo.isCarrierMerged) {
                WifiNetworkModel.CarrierMerged
            } else {
                WifiNetworkModel.Active(
                    network.getNetId(),
                    isValidated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED),
                    level = wifiManager.calculateSignalLevel(wifiInfo.rssi),
                    wifiInfo.ssid,
                    wifiInfo.isPasspointAp,
                    wifiInfo.isOsuAp,
                    wifiInfo.passpointProviderFriendlyName
                )
            }
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

        private val WIFI_NETWORK_CALLBACK_REQUEST: NetworkRequest =
            NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_WIFI)
                .addTransportType(TRANSPORT_CELLULAR)
                .build()

        private const val WIFI_NETWORK_CALLBACK_NAME = "wifiNetworkModel"
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        private val broadcastDispatcher: BroadcastDispatcher,
        private val connectivityManager: ConnectivityManager,
        private val logger: ConnectivityPipelineLogger,
        @WifiTableLog private val wifiTableLogBuffer: TableLogBuffer,
        @Main private val mainExecutor: Executor,
        @Application private val scope: CoroutineScope,
    ) {
        fun create(wifiManager: WifiManager): WifiRepositoryImpl {
            return WifiRepositoryImpl(
                broadcastDispatcher,
                connectivityManager,
                logger,
                wifiTableLogBuffer,
                mainExecutor,
                scope,
                wifiManager,
            )
        }
    }
}
