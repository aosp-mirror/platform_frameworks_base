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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A repository implementation for a carrier merged (aka VCN) network. A carrier merged network is
 * delivered to SysUI as a wifi network (see [WifiNetworkModel.CarrierMerged], but is visually
 * displayed as a mobile network triangle.
 *
 * See [android.net.wifi.WifiInfo.isCarrierMerged] for more information.
 *
 * See [MobileConnectionRepositoryImpl] for a repository implementation of a typical mobile
 * connection.
 */
class CarrierMergedConnectionRepository(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
    defaultNetworkName: NetworkNameModel,
    @Application private val scope: CoroutineScope,
    val wifiRepository: WifiRepository,
) : MobileConnectionRepository {

    /**
     * Outputs the carrier merged network to use, or null if we don't have a valid carrier merged
     * network.
     */
    private val network: Flow<WifiNetworkModel.CarrierMerged?> =
        combine(
            wifiRepository.isWifiEnabled,
            wifiRepository.isWifiDefault,
            wifiRepository.wifiNetwork,
        ) { isEnabled, isDefault, network ->
            when {
                !isEnabled -> null
                !isDefault -> null
                network !is WifiNetworkModel.CarrierMerged -> null
                network.subscriptionId != subId -> {
                    Log.w(
                        TAG,
                        "Connection repo subId=$subId " +
                            "does not equal wifi repo subId=${network.subscriptionId}; " +
                            "not showing carrier merged"
                    )
                    null
                }
                else -> network
            }
        }

    override val connectionInfo: StateFlow<MobileConnectionModel> =
        network
            .map { it.toMobileConnectionModel() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), MobileConnectionModel())

    // TODO(b/238425913): Add logging to this class.
    // TODO(b/238425913): Make sure SignalStrength.getEmptyState is used when appropriate.

    // Carrier merged is never roaming.
    override val cdmaRoaming: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    // TODO(b/238425913): Fetch the carrier merged network name.
    override val networkName: StateFlow<NetworkNameModel> =
        flowOf(defaultNetworkName)
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    override val numberOfLevels: StateFlow<Int> =
        wifiRepository.wifiNetwork
            .map {
                if (it is WifiNetworkModel.CarrierMerged) {
                    it.numberOfLevels
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    override val dataEnabled: StateFlow<Boolean> = wifiRepository.isWifiEnabled

    private fun WifiNetworkModel.CarrierMerged?.toMobileConnectionModel(): MobileConnectionModel {
        if (this == null) {
            return MobileConnectionModel()
        }

        return createCarrierMergedConnectionModel(level)
    }

    companion object {
        /**
         * Creates an instance of [MobileConnectionModel] that represents a carrier merged network
         * with the given [level].
         */
        fun createCarrierMergedConnectionModel(level: Int): MobileConnectionModel {
            return MobileConnectionModel(
                primaryLevel = level,
                cdmaLevel = level,
                // A [WifiNetworkModel.CarrierMerged] instance is always connected.
                // (A [WifiNetworkModel.Inactive] represents a disconnected network.)
                dataConnectionState = DataConnectionState.Connected,
                // TODO(b/238425913): This should come from [WifiRepository.wifiActivity].
                dataActivityDirection =
                    DataActivityModel(
                        hasActivityIn = false,
                        hasActivityOut = false,
                    ),
                resolvedNetworkType = ResolvedNetworkType.CarrierMergedNetworkType,
                // Carrier merged is never roaming
                isRoaming = false,

                // TODO(b/238425913): Verify that these fields never change for carrier merged.
                isEmergencyOnly = false,
                operatorAlphaShort = null,
                isInService = true,
                isGsm = false,
                carrierNetworkChangeActive = false,
            )
        }
    }

    @SysUISingleton
    class Factory
    @Inject
    constructor(
        @Application private val scope: CoroutineScope,
        private val wifiRepository: WifiRepository,
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            defaultNetworkName: NetworkNameModel,
        ): MobileConnectionRepository {
            return CarrierMergedConnectionRepository(
                subId,
                mobileLogger,
                defaultNetworkName,
                scope,
                wifiRepository,
            )
        }
    }
}

private const val TAG = "CarrierMergedConnectionRepository"
