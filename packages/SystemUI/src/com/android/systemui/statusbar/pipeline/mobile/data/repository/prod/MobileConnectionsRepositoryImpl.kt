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
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.android.internal.telephony.PhoneConstants
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.statusbar.pipeline.dagger.MobileSummaryLog
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.kotlin.pairwise
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
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
    private val logger: MobileInputLogger,
    @MobileSummaryLog private val tableLogger: TableLogBuffer,
    mobileMappingsProxy: MobileMappingsProxy,
    broadcastDispatcher: BroadcastDispatcher,
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    // Some "wifi networks" should be rendered as a mobile connection, which is why the wifi
    // repository is an input to the mobile repository.
    // See [CarrierMergedConnectionRepository] for details.
    wifiRepository: WifiRepository,
    private val fullMobileRepoFactory: FullMobileConnectionRepository.Factory,
) : MobileConnectionsRepository {
    private var subIdRepositoryCache: MutableMap<Int, FullMobileConnectionRepository> =
        mutableMapOf()

    private val defaultNetworkName =
        NetworkNameModel.Default(
            context.getString(com.android.internal.R.string.lockscreen_carrier_default)
        )

    private val networkNameSeparator: String =
        context.getString(R.string.status_bar_network_name_separator)

    private val carrierMergedSubId: StateFlow<Int?> =
        wifiRepository.wifiNetwork
            .mapLatest {
                if (it is WifiNetworkModel.CarrierMerged) {
                    it.subscriptionId
                } else {
                    null
                }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "carrierMergedSubId",
                initialValue = null,
            )
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), null)

    private val mobileSubscriptionsChangeEvent: Flow<Unit> = conflatedCallbackFlow {
        val callback =
            object : SubscriptionManager.OnSubscriptionsChangedListener() {
                override fun onSubscriptionsChanged() {
                    logger.logOnSubscriptionsChanged()
                    trySend(Unit)
                }
            }

        subscriptionManager.addOnSubscriptionsChangedListener(
            bgDispatcher.asExecutor(),
            callback,
        )

        awaitClose { subscriptionManager.removeOnSubscriptionsChangedListener(callback) }
    }

    /**
     * State flow that emits the set of mobile data subscriptions, each represented by its own
     * [SubscriptionModel].
     */
    override val subscriptions: StateFlow<List<SubscriptionModel>> =
        merge(mobileSubscriptionsChangeEvent, carrierMergedSubId)
            .mapLatest { fetchSubscriptionsList().map { it.toSubscriptionModel() } }
            .onEach { infos -> updateRepos(infos) }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "subscriptions",
                initialValue = listOf(),
            )
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), listOf())

    override val activeMobileDataSubscriptionId: StateFlow<Int?> =
        conflatedCallbackFlow {
                val callback =
                    object : TelephonyCallback(), ActiveDataSubscriptionIdListener {
                        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
                            if (subId != INVALID_SUBSCRIPTION_ID) {
                                trySend(subId)
                            } else {
                                trySend(null)
                            }
                        }
                    }

                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "activeSubId",
                initialValue = INVALID_SUBSCRIPTION_ID,
            )
            .stateIn(scope, started = SharingStarted.WhileSubscribed(), null)

    override val activeMobileDataRepository =
        activeMobileDataSubscriptionId
            .map { activeSubId ->
                if (activeSubId == null) {
                    null
                } else {
                    getOrCreateRepoForSubId(activeSubId)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    private val defaultDataSubIdChangeEvent: MutableSharedFlow<Unit> =
        MutableSharedFlow(extraBufferCapacity = 1)

    override val defaultDataSubId: StateFlow<Int> =
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
            ) { intent, _ ->
                intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, INVALID_SUBSCRIPTION_ID)
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "defaultSubId",
                initialValue = SubscriptionManager.getDefaultDataSubscriptionId(),
            )
            .onEach { defaultDataSubIdChangeEvent.tryEmit(Unit) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                SubscriptionManager.getDefaultDataSubscriptionId()
            )

    private val carrierConfigChangedEvent =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED))
            .onEach { logger.logActionCarrierConfigChanged() }

    override val defaultDataSubRatConfig: StateFlow<Config> =
        merge(defaultDataSubIdChangeEvent, carrierConfigChangedEvent)
            .mapLatest { Config.readConfig(context) }
            .distinctUntilChanged()
            .onEach { logger.logDefaultDataSubRatConfig(it) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                initialValue = Config.readConfig(context)
            )

    override val defaultMobileIconMapping: Flow<Map<String, MobileIconGroup>> =
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.mapIconSets(it) }
            .distinctUntilChanged()
            .onEach { logger.logDefaultMobileIconMapping(it) }

    override val defaultMobileIconGroup: Flow<MobileIconGroup> =
        defaultDataSubRatConfig
            .map { mobileMappingsProxy.getDefaultIcons(it) }
            .distinctUntilChanged()
            .onEach { logger.logDefaultMobileIconGroup(it) }

    override fun getRepoForSubId(subId: Int): FullMobileConnectionRepository {
        if (!isValidSubId(subId)) {
            throw IllegalArgumentException(
                "subscriptionId $subId is not in the list of valid subscriptions"
            )
        }

        return getOrCreateRepoForSubId(subId)
    }

    private fun getOrCreateRepoForSubId(subId: Int) =
        subIdRepositoryCache[subId]
            ?: createRepositoryForSubId(subId).also { subIdRepositoryCache[subId] = it }

    @SuppressLint("MissingPermission")
    override val defaultMobileNetworkConnectivity: StateFlow<MobileConnectivityModel> =
        conflatedCallbackFlow {
                val callback =
                    object : NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                        override fun onLost(network: Network) {
                            logger.logOnLost(network, isDefaultNetworkCallback = true)
                            // Send a disconnected model when lost. Maybe should create a sealed
                            // type or null here?
                            trySend(MobileConnectivityModel())
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            caps: NetworkCapabilities
                        ) {
                            logger.logOnCapabilitiesChanged(
                                network,
                                caps,
                                isDefaultNetworkCallback = true,
                            )
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
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "$LOGGING_PREFIX.defaultConnection",
                initialValue = MobileConnectivityModel(),
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), MobileConnectivityModel())

    /**
     * Flow that tracks the active mobile data subscriptions. Emits `true` whenever the active data
     * subscription Id changes but the subscription group remains the same. In these cases, we want
     * to retain the previous subscription's validation status for up to 2s to avoid flickering the
     * icon.
     *
     * TODO(b/265164432): we should probably expose all change events, not just same group
     */
    @SuppressLint("MissingPermission")
    override val activeSubChangedInGroupEvent =
        activeMobileDataSubscriptionId
            .pairwise()
            .mapNotNull { (prevVal: Int?, newVal: Int?) ->
                if (prevVal == null || newVal == null) return@mapNotNull null

                val prevSub = subscriptionManager.getActiveSubscriptionInfo(prevVal)?.groupUuid
                val nextSub = subscriptionManager.getActiveSubscriptionInfo(newVal)?.groupUuid

                if (prevSub != null && prevSub == nextSub) Unit else null
            }
            .flowOn(bgDispatcher)

    private fun isValidSubId(subId: Int): Boolean = checkSub(subId, subscriptions.value)

    @VisibleForTesting fun getSubIdRepoCache() = subIdRepositoryCache

    private fun createRepositoryForSubId(subId: Int): FullMobileConnectionRepository {
        return fullMobileRepoFactory.build(
            subId,
            isCarrierMerged(subId),
            defaultNetworkName,
            networkNameSeparator,
        )
    }

    private fun updateRepos(newInfos: List<SubscriptionModel>) {
        dropUnusedReposFromCache(newInfos)
        subIdRepositoryCache.forEach { (subId, repo) ->
            repo.setIsCarrierMerged(isCarrierMerged(subId))
        }
    }

    private fun isCarrierMerged(subId: Int): Boolean {
        return subId == carrierMergedSubId.value
    }

    private fun dropUnusedReposFromCache(newInfos: List<SubscriptionModel>) {
        // Remove any connection repository from the cache that isn't in the new set of IDs. They
        // will get garbage collected once their subscribers go away
        subIdRepositoryCache =
            subIdRepositoryCache.filter { checkSub(it.key, newInfos) }.toMutableMap()
    }

    /**
     * True if the checked subId is in the list of current subs or the active mobile data subId
     *
     * @param checkedSubs the list to validate [subId] against. To invalidate the cache, pass in the
     *   new subscription list. Otherwise use [subscriptions.value] to validate a subId against the
     *   current known subscriptions
     */
    private fun checkSub(subId: Int, checkedSubs: List<SubscriptionModel>): Boolean {
        if (activeMobileDataSubscriptionId.value == subId) return true

        checkedSubs.forEach {
            if (it.subscriptionId == subId) {
                return true
            }
        }

        return false
    }

    private suspend fun fetchSubscriptionsList(): List<SubscriptionInfo> =
        withContext(bgDispatcher) { subscriptionManager.completeActiveSubscriptionInfoList }

    private fun SubscriptionInfo.toSubscriptionModel(): SubscriptionModel =
        SubscriptionModel(
            subscriptionId = subscriptionId,
            isOpportunistic = isOpportunistic,
            groupUuid = groupUuid,
        )

    companion object {
        private const val LOGGING_PREFIX = "Repo"
    }
}
