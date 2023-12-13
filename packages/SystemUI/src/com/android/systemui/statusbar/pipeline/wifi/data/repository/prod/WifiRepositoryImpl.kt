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
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.dagger.WifiTableLog
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.getMainOrUnderlyingWifiInfo
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.CARRIER_MERGED_INVALID_SUB_ID_REASON
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository.Companion.COL_NAME_IS_ENABLED
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryDagger
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiInputLogger
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    connectivityRepository: ConnectivityRepository,
    logger: WifiInputLogger,
    @WifiTableLog wifiTableLogBuffer: TableLogBuffer,
    @Main mainExecutor: Executor,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val wifiManager: WifiManager,
) : WifiRepositoryDagger {

    override fun start() {
        // There are two possible [WifiRepository] implementations: This class (old) and
        // [WifiRepositoryFromTrackerLib] (new). While we migrate to the new class, we want this old
        // class to still be running in the background so that we can collect logs and compare
        // discrepancies. This #start method collects on the flows to ensure that the logs are
        // collected.
        scope.launch { isWifiEnabled.collect {} }
        scope.launch { isWifiDefault.collect {} }
        scope.launch { wifiNetwork.collect {} }
        scope.launch { wifiActivity.collect {} }
    }

    private val wifiStateChangeEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
            .onEach { logger.logIntent("WIFI_STATE_CHANGED_ACTION") }

    private val wifiNetworkChangeEvents: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    // Because [WifiManager] doesn't expose a wifi enabled change listener, we do it
    // internally by fetching [WifiManager.isWifiEnabled] whenever we think the state may
    // have changed.
    override val isWifiEnabled: StateFlow<Boolean> =
        merge(wifiNetworkChangeEvents, wifiStateChangeEvents)
            .onStart { emit(Unit) }
            .mapLatest { isWifiEnabled() }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "",
                columnName = COL_NAME_IS_ENABLED,
                initialValue = false,
            )
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = false,
            )

    // [WifiManager.isWifiEnabled] is a blocking IPC call, so fetch it in the background.
    private suspend fun isWifiEnabled(): Boolean =
        withContext(bgDispatcher) { wifiManager.isWifiEnabled }

    override val isWifiDefault: StateFlow<Boolean> =
        connectivityRepository.defaultConnections
            // TODO(b/274493701): Should wifi be considered default if it's carrier merged?
            .map { it.wifi.isDefault || it.carrierMerged.isDefault }
            .distinctUntilChanged()
            .logDiffsForTable(
                wifiTableLogBuffer,
                columnPrefix = "",
                columnName = COL_NAME_IS_DEFAULT,
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
                            logger.logOnCapabilitiesChanged(
                                network,
                                networkCapabilities,
                                isDefaultNetworkCallback = false,
                            )

                            wifiNetworkChangeEvents.tryEmit(Unit)

                            val wifiInfo =
                                networkCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)
                            if (wifiInfo?.isPrimary == true) {
                                val wifiNetworkModel =
                                    createWifiNetworkModel(
                                        wifiInfo,
                                        network,
                                        networkCapabilities,
                                        wifiManager,
                                    )
                                currentWifi = wifiNetworkModel
                                trySend(wifiNetworkModel)
                            }
                        }

                        override fun onLost(network: Network) {
                            logger.logOnLost(network, isDefaultNetworkCallback = false)

                            wifiNetworkChangeEvents.tryEmit(Unit)

                            val wifi = currentWifi
                            if (
                                (wifi is WifiNetworkModel.Active &&
                                    wifi.networkId == network.getNetId()) ||
                                    (wifi is WifiNetworkModel.CarrierMerged &&
                                        wifi.networkId == network.getNetId())
                            ) {
                                val newNetworkModel = WifiNetworkModel.Inactive
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
                columnPrefix = "",
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

    // Secondary networks can only be supported by [WifiRepositoryViaTrackerLib].
    override val secondaryNetworks: StateFlow<List<WifiNetworkModel>> =
        MutableStateFlow(emptyList<WifiNetworkModel>()).asStateFlow()

    override val wifiActivity: StateFlow<DataActivityModel> =
        WifiRepositoryHelper.createActivityFlow(
            wifiManager,
            mainExecutor,
            scope,
            wifiTableLogBuffer,
            logger::logActivity,
        )

    override val wifiScanResults: StateFlow<List<WifiScanEntry>> =
        WifiRepositoryHelper.createNetworkScanFlow(
            wifiManager,
            scope,
            bgDispatcher,
            logger::logScanResults
        )

    companion object {
        // Start out with no known wifi network.
        // Note: [WifiStatusTracker] (the old implementation of connectivity logic) does do an
        // initial fetch to get a starting wifi network. But, it uses a deprecated API
        // [WifiManager.getConnectionInfo()], and the deprecation doc indicates to just use
        // [ConnectivityManager.NetworkCallback] results instead. So, for now we'll just rely on the
        // NetworkCallback inside [wifiNetwork] for our wifi network information.
        val WIFI_NETWORK_DEFAULT = WifiNetworkModel.Inactive

        const val WIFI_STATE_DEFAULT = WifiManager.WIFI_STATE_DISABLED

        private fun createWifiNetworkModel(
            wifiInfo: WifiInfo,
            network: Network,
            networkCapabilities: NetworkCapabilities,
            wifiManager: WifiManager,
        ): WifiNetworkModel {
            return if (wifiInfo.isCarrierMerged) {
                if (wifiInfo.subscriptionId == INVALID_SUBSCRIPTION_ID) {
                    WifiNetworkModel.Invalid(CARRIER_MERGED_INVALID_SUB_ID_REASON)
                } else {
                    WifiNetworkModel.CarrierMerged(
                        networkId = network.getNetId(),
                        subscriptionId = wifiInfo.subscriptionId,
                        level = wifiManager.calculateSignalLevel(wifiInfo.rssi),
                        // The WiFi signal level returned by WifiManager#calculateSignalLevel start
                        // from 0, so WifiManager#getMaxSignalLevel + 1 represents the total level
                        // buckets count.
                        numberOfLevels = wifiManager.maxSignalLevel + 1,
                    )
                }
            } else {
                WifiNetworkModel.Active(
                    network.getNetId(),
                    isValidated = networkCapabilities.hasCapability(NET_CAPABILITY_VALIDATED),
                    level = wifiManager.calculateSignalLevel(wifiInfo.rssi),
                    wifiInfo.ssid,
                    // This repository doesn't support any hotspot information.
                    WifiNetworkModel.HotspotDeviceType.NONE,
                    wifiInfo.isPasspointAp,
                    wifiInfo.isOsuAp,
                    wifiInfo.passpointProviderFriendlyName
                )
            }
        }

        private val WIFI_NETWORK_CALLBACK_REQUEST: NetworkRequest =
            NetworkRequest.Builder()
                .clearCapabilities()
                .addCapability(NET_CAPABILITY_NOT_VPN)
                .addTransportType(TRANSPORT_WIFI)
                .addTransportType(TRANSPORT_CELLULAR)
                .build()
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        private val broadcastDispatcher: BroadcastDispatcher,
        private val connectivityManager: ConnectivityManager,
        private val connectivityRepository: ConnectivityRepository,
        private val logger: WifiInputLogger,
        @WifiTableLog private val wifiTableLogBuffer: TableLogBuffer,
        @Main private val mainExecutor: Executor,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
    ) {
        fun create(wifiManager: WifiManager): WifiRepositoryImpl {
            return WifiRepositoryImpl(
                broadcastDispatcher,
                connectivityManager,
                connectivityRepository,
                logger,
                wifiTableLogBuffer,
                mainExecutor,
                bgDispatcher,
                scope,
                wifiManager,
            )
        }
    }
}
