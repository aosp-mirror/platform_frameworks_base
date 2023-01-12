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

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.provider.Settings.Global.MOBILE_DATA
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logInputChange
import com.android.systemui.util.settings.GlobalSettings
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MobileConnectionsRepositoryImpl
@Inject
constructor(
    private val connectivityManager: ConnectivityManager,
    private val subscriptionManager: SubscriptionManager,
    private val telephonyManager: TelephonyManager,
    private val logger: ConnectivityPipelineLogger,
    mobileMappingsProxy: MobileMappingsProxy,
    broadcastDispatcher: BroadcastDispatcher,
    private val globalSettings: GlobalSettings,
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    private val mobileConnectionRepositoryFactory: MobileConnectionRepositoryImpl.Factory
) : MobileConnectionsRepository {
    private var subIdRepositoryCache: MutableMap<Int, MobileConnectionRepository> = mutableMapOf()

    private val defaultNetworkName =
        NetworkNameModel.Default(
            context.getString(com.android.internal.R.string.lockscreen_carrier_default)
        )

    private val networkNameSeparator: String =
        context.getString(R.string.status_bar_network_name_separator)

    /**
     * State flow that emits the set of mobile data subscriptions, each represented by its own
     * [SubscriptionInfo]. We probably only need the [SubscriptionInfo.getSubscriptionId] of each
     * info object, but for now we keep track of the infos themselves.
     */
    override val subscriptions: StateFlow<List<SubscriptionModel>> =
        conflatedCallbackFlow {
                val callback =
                    object : SubscriptionManager.OnSubscriptionsChangedListener() {
                        override fun onSubscriptionsChanged() {
                            trySend(Unit)
                        }
                    }

                subscriptionManager.addOnSubscriptionsChangedListener(
                    bgDispatcher.asExecutor(),
                    callback,
                )

                awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(callback) }
            }
            .mapLatest { fetchSubscriptionsList().map { it.toSubscriptionModel() } }
            .logInputChange(logger, "onSubscriptionsChanged")
            .onEach { infos -> dropUnusedReposFromCache(infos) }
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), listOf())

    /** StateFlow that keeps track of the current active mobile data subscription */
    override val activeMobileDataSubscriptionId: StateFlow<Int> =
        conflatedCallbackFlow {
                val callback =
                    object : TelephonyCallback(), ActiveDataSubscriptionIdListener {
                        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                            trySend(subId)
                        }
                    }

                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .distinctUntilChanged()
            .logInputChange(logger, "onActiveDataSubscriptionIdChanged")
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), INVALID_SUBSCRIPTION_ID)

    private val defaultDataSubIdChangedEvent =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED))
            .logInputChange(logger, "ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED")

    private val carrierConfigChangedEvent =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED))
            .logInputChange(logger, "ACTION_CARRIER_CONFIG_CHANGED")

    override val defaultDataSubRatConfig: StateFlow<Config> =
        merge(defaultDataSubIdChangedEvent, carrierConfigChangedEvent)
            .mapLatest { Config.readConfig(context) }
            .distinctUntilChanged()
            .logInputChange(logger, "defaultDataSubRatConfig")
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = Config.readConfig(context)
            )

    override val defaultMobileIconMapping: Flow<Map<String, MobileIconGroup>> =
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.mapIconSets(it) }
            .distinctUntilChanged()
            .logInputChange(logger, "defaultMobileIconMapping")

    override val defaultMobileIconGroup: Flow<MobileIconGroup> =
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.getDefaultIcons(it) }
            .distinctUntilChanged()
            .logInputChange(logger, "defaultMobileIconGroup")

    override fun getRepoForSubId(subId: Int): MobileConnectionRepository {
        if (!isValidSubId(subId)) {
            throw IllegalArgumentException(
                "subscriptionId $subId is not in the list of valid subscriptions"
            )
        }

        return subIdRepositoryCache[subId]
            ?: createRepositoryForSubId(subId).also { subIdRepositoryCache[subId] = it }
    }

    /**
     * In single-SIM devices, the [MOBILE_DATA] setting is phone-wide. For multi-SIM, the individual
     * connection repositories also observe the URI for [MOBILE_DATA] + subId.
     */
    override val globalMobileDataSettingChangedEvent: Flow<Unit> =
        conflatedCallbackFlow {
                val observer =
                    object : ContentObserver(null) {
                        override fun onChange(selfChange: Boolean) {
                            trySend(Unit)
                        }
                    }

                globalSettings.registerContentObserver(
                    globalSettings.getUriFor(MOBILE_DATA),
                    true,
                    observer
                )

                awaitClose { context.contentResolver.unregisterContentObserver(observer) }
            }
            .logInputChange(logger, "globalMobileDataSettingChangedEvent")

    @SuppressLint("MissingPermission")
    override val defaultMobileNetworkConnectivity: StateFlow<MobileConnectivityModel> =
        conflatedCallbackFlow {
                val callback =
                    object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onLost(network: Network) {
                            // Send a disconnected model when lost. Maybe should create a sealed
                            // type or null here?
                            trySend(MobileConnectivityModel())
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities
                        ) {
                            trySend(
                                MobileConnectivityModel(
                                    isConnected = caps.hasTransport(TRANSPORT_CELLULAR),
                                    isValidated = caps.hasCapability(NET_CAPABILITY_VALIDATED),
                                )
                            )
                        }
                    }

                connectivityManager.registerDefaultNetworkCallback(callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .distinctUntilChanged()
            .logInputChange(logger, "defaultMobileNetworkConnectivity")
            .stateIn(scope, SharingStarted.WhileSubscribed(), MobileConnectivityModel())

    private fun isValidSubId(subId: Int): Boolean {
        subscriptions.value.forEach {
            if (it.subscriptionId == subId) {
                return true
            }
        }

        return false
    }

    @VisibleForTesting fun getSubIdRepoCache() = subIdRepositoryCache

    private fun createRepositoryForSubId(subId: Int): MobileConnectionRepository {
        return mobileConnectionRepositoryFactory.build(
            subId,
            defaultNetworkName,
            networkNameSeparator,
            globalMobileDataSettingChangedEvent,
        )
    }

    private fun dropUnusedReposFromCache(newInfos: List<SubscriptionModel>) {
        // Remove any connection repository from the cache that isn't in the new set of IDs. They
        // will get garbage collected once their subscribers go away
        val currentValidSubscriptionIds = newInfos.map { it.subscriptionId }

        subIdRepositoryCache =
            subIdRepositoryCache
                .filter { currentValidSubscriptionIds.contains(it.key) }
                .toMutableMap()
    }

    private suspend fun fetchSubscriptionsList(): List<SubscriptionInfo> =
        withContext(bgDispatcher) { subscriptionManager.completeActiveSubscriptionInfoList }

    private fun SubscriptionInfo.toSubscriptionModel(): SubscriptionModel =
        SubscriptionModel(
            subscriptionId = subscriptionId,
            isOpportunistic = isOpportunistic,
        )
}
