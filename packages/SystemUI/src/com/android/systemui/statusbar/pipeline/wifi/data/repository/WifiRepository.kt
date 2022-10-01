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
import android.util.Log
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.SB_LOGGING_TAG
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logInputChange
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiActivityModel
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn

/** Provides data related to the wifi state. */
interface WifiRepository {
    /** Observable for the current wifi enabled status. */
    val isWifiEnabled: StateFlow<Boolean>

    /** Observable for the current wifi network. */
    val wifiNetwork: StateFlow<WifiNetworkModel>

    /** Observable for the current wifi network activity. */
    val wifiActivity: StateFlow<WifiActivityModel>
}

/** Real implementation of [WifiRepository]. */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
@SuppressLint("MissingPermission")
class WifiRepositoryImpl @Inject constructor(
    broadcastDispatcher: BroadcastDispatcher,
    connectivityManager: ConnectivityManager,
    logger: ConnectivityPipelineLogger,
    @Main mainExecutor: Executor,
    @Application scope: CoroutineScope,
    wifiManager: WifiManager?,
) : WifiRepository {

    private val wifiStateChangeEvents: Flow<Unit> = broadcastDispatcher.broadcastFlow(
        IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
    )
        .logInputChange(logger, "WIFI_STATE_CHANGED_ACTION intent")

    private val wifiNetworkChangeEvents: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    override val isWifiEnabled: StateFlow<Boolean> =
        if (wifiManager == null) {
            MutableStateFlow(false).asStateFlow()
        } else {
            // Because [WifiManager] doesn't expose a wifi enabled change listener, we do it
            // internally by fetching [WifiManager.isWifiEnabled] whenever we think the state may
            // have changed.
            merge(wifiNetworkChangeEvents, wifiStateChangeEvents)
                .mapLatest { wifiManager.isWifiEnabled }
                .distinctUntilChanged()
                .logOutputChange(logger, "enabled")
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = wifiManager.isWifiEnabled
                )
        }

    override val wifiNetwork: StateFlow<WifiNetworkModel> = conflatedCallbackFlow {
        var currentWifi: WifiNetworkModel = WIFI_NETWORK_DEFAULT

        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                logger.logOnCapabilitiesChanged(network, networkCapabilities)

                wifiNetworkChangeEvents.tryEmit(Unit)

                val wifiInfo = networkCapabilitiesToWifiInfo(networkCapabilities)
                if (wifiInfo?.isPrimary == true) {
                    val wifiNetworkModel = createWifiNetworkModel(
                        wifiInfo,
                        network,
                        networkCapabilities,
                        wifiManager,
                    )
                    logger.logTransformation(
                        WIFI_NETWORK_CALLBACK_NAME,
                        oldValue = currentWifi,
                        newValue = wifiNetworkModel
                    )
                    currentWifi = wifiNetworkModel
                    trySend(wifiNetworkModel)
                }
            }

            override fun onLost(network: Network) {
                logger.logOnLost(network)

                wifiNetworkChangeEvents.tryEmit(Unit)

                val wifi = currentWifi
                if (wifi is WifiNetworkModel.Active && wifi.networkId == network.getNetId()) {
                    val newNetworkModel = WifiNetworkModel.Inactive
                    logger.logTransformation(
                        WIFI_NETWORK_CALLBACK_NAME,
                        oldValue = wifi,
                        newValue = newNetworkModel
                    )
                    currentWifi = newNetworkModel
                    trySend(newNetworkModel)
                }
            }
        }

        connectivityManager.registerNetworkCallback(WIFI_NETWORK_CALLBACK_REQUEST, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }
        // There will be multiple wifi icons in different places that will frequently
        // subscribe/unsubscribe to flows as the views attach/detach. Using [stateIn] ensures that
        // new subscribes will get the latest value immediately upon subscription. Otherwise, the
        // views could show stale data. See b/244173280.
        .stateIn(
            scope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = WIFI_NETWORK_DEFAULT
        )

    override val wifiActivity: StateFlow<WifiActivityModel> =
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
                    awaitClose { wifiManager.unregisterTrafficStateCallback(callback) }
                }
            }
                .stateIn(
                    scope,
                    started = SharingStarted.WhileSubscribed(),
                    initialValue = ACTIVITY_DEFAULT
                )

    companion object {
        val ACTIVITY_DEFAULT = WifiActivityModel(hasActivityIn = false, hasActivityOut = false)
        // Start out with no known wifi network.
        // Note: [WifiStatusTracker] (the old implementation of connectivity logic) does do an
        // initial fetch to get a starting wifi network. But, it uses a deprecated API
        // [WifiManager.getConnectionInfo()], and the deprecation doc indicates to just use
        // [ConnectivityManager.NetworkCallback] results instead. So, for now we'll just rely on the
        // NetworkCallback inside [wifiNetwork] for our wifi network information.
        val WIFI_NETWORK_DEFAULT = WifiNetworkModel.Inactive

        private fun trafficStateToWifiActivityModel(state: Int): WifiActivityModel {
            return WifiActivityModel(
                hasActivityIn = state == TrafficStateCallback.DATA_ACTIVITY_IN ||
                    state == TrafficStateCallback.DATA_ACTIVITY_INOUT,
                hasActivityOut = state == TrafficStateCallback.DATA_ACTIVITY_OUT ||
                    state == TrafficStateCallback.DATA_ACTIVITY_INOUT,
            )
        }

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
            wifiManager: WifiManager?,
        ): WifiNetworkModel {
            return if (wifiInfo.isCarrierMerged) {
                WifiNetworkModel.CarrierMerged
            } else {
                WifiNetworkModel.Active(
                        network.getNetId(),
                        isValidated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED),
                        level = wifiManager?.calculateSignalLevel(wifiInfo.rssi),
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
}
