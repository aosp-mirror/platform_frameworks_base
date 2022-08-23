/*
 * Copyright (C) 2021 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted.Companion.Lazily
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Repository that contains all relevant [NetworkCapabilities] for the current networks.
 *
 * TODO(b/238425913): Figure out how to merge this with [WifiRepository].
 */
@SysUISingleton
class NetworkCapabilitiesRepo @Inject constructor(
    connectivityManager: ConnectivityManager,
    @Application scope: CoroutineScope,
    logger: ConnectivityPipelineLogger,
) {
    @SuppressLint("MissingPermission")
    val dataStream: StateFlow<Map<Int, NetworkCapabilityInfo>> = run {
        var state = emptyMap<Int, NetworkCapabilityInfo>()
        callbackFlow {
                val networkRequest: NetworkRequest =
                    NetworkRequest.Builder()
                        .clearCapabilities()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                        .build()
                val callback =
                    // TODO (b/240569788): log these using [LogBuffer]
                    object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities
                        ) {
                            logger.logOnCapabilitiesChanged(network, networkCapabilities)
                            state =
                                state.toMutableMap().also {
                                    it[network.getNetId()] =
                                        NetworkCapabilityInfo(network, networkCapabilities)
                                }
                            trySend(state)
                        }

                        override fun onLost(network: Network) {
                            logger.logOnLost(network)
                            state = state.toMutableMap().also { it.remove(network.getNetId()) }
                            trySend(state)
                        }
                    }
                connectivityManager.registerNetworkCallback(networkRequest, callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .stateIn(scope, started = Lazily, initialValue = state)
    }
}

/** contains info about network capabilities. */
data class NetworkCapabilityInfo(
    val network: Network,
    val capabilities: NetworkCapabilities,
)
