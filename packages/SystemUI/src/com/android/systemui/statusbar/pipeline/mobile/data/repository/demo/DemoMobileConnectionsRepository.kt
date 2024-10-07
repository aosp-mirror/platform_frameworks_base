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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.demo

import android.content.Context
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import android.util.Log
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.Mobile
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.MobileDisabled
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Factory.Companion.MOBILE_CONNECTION_BUFFER_SIZE
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoModeWifiDataSource
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** This repository vends out data based on demo mode commands */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoMobileConnectionsRepository
@Inject
constructor(
    private val mobileDataSource: DemoModeMobileConnectionDataSource,
    private val wifiDataSource: DemoModeWifiDataSource,
    @Application private val scope: CoroutineScope,
    context: Context,
    private val logFactory: TableLogBufferFactory,
) : MobileConnectionsRepository {

    private var mobileDemoCommandJob: Job? = null
    private var wifiDemoCommandJob: Job? = null

    private var carrierMergedSubId: Int? = null

    private var connectionRepoCache = mutableMapOf<Int, CacheContainer>()
    private val subscriptionInfoCache = mutableMapOf<Int, SubscriptionModel>()
    val demoModeFinishedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _subscriptions = MutableStateFlow<List<SubscriptionModel>>(listOf())
    override val subscriptions =
        _subscriptions
            .onEach { infos -> dropUnusedReposFromCache(infos) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), _subscriptions.value)

    private fun dropUnusedReposFromCache(newInfos: List<SubscriptionModel>) {
        // Remove any connection repository from the cache that isn't in the new set of IDs. They
        // will get garbage collected once their subscribers go away
        val currentValidSubscriptionIds = newInfos.map { it.subscriptionId }

        connectionRepoCache =
            connectionRepoCache
                .filter { currentValidSubscriptionIds.contains(it.key) }
                .toMutableMap()
    }

    private fun maybeCreateSubscription(subId: Int) {
        if (!subscriptionInfoCache.containsKey(subId)) {
            SubscriptionModel(
                    subscriptionId = subId,
                    isOpportunistic = false,
                    carrierName = DEFAULT_CARRIER_NAME,
                    profileClass = PROFILE_CLASS_UNSET,
                )
                .also { subscriptionInfoCache[subId] = it }

            _subscriptions.value = subscriptionInfoCache.values.toList()
        }
    }

    // TODO(b/261029387): add a command for this value
    override val activeMobileDataSubscriptionId =
        subscriptions
            .mapLatest { infos ->
                // For now, active is just the first in the list
                infos.firstOrNull()?.subscriptionId ?: INVALID_SUBSCRIPTION_ID
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                subscriptions.value.firstOrNull()?.subscriptionId ?: INVALID_SUBSCRIPTION_ID
            )

    override val activeMobileDataRepository: StateFlow<MobileConnectionRepository?> =
        activeMobileDataSubscriptionId
            .map { getRepoForSubId(it) }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                getRepoForSubId(activeMobileDataSubscriptionId.value)
            )

    // TODO(b/261029387): consider adding a demo command for this
    override val activeSubChangedInGroupEvent: Flow<Unit> = flowOf()

    /** Demo mode doesn't currently support modifications to the mobile mappings */
    override val defaultDataSubRatConfig =
        MutableStateFlow(MobileMappings.Config.readConfig(context))

    override val defaultMobileIconGroup = flowOf(TelephonyIcons.THREE_G)

    // TODO(b/339023069): demo command for device-based emergency calls state
    override val isDeviceEmergencyCallCapable: StateFlow<Boolean> = MutableStateFlow(false)

    override val isAnySimSecure: Flow<Boolean> = flowOf(getIsAnySimSecure())

    override fun getIsAnySimSecure(): Boolean = false

    override val defaultMobileIconMapping = MutableStateFlow(TelephonyIcons.ICON_NAME_TO_ICON)

    /**
     * In order to maintain compatibility with the old demo mode shell command API, reverse the
     * [MobileMappings] lookup from (NetworkType: String -> Icon: MobileIconGroup), so that we can
     * parse the string from the command line into a preferred icon group, and send _a_ valid
     * network type for that icon through the pipeline.
     *
     * Note: collisions don't matter here, because the data source (the command line) only cares
     * about the resulting icon, not the underlying network type.
     */
    private val mobileMappingsReverseLookup: StateFlow<Map<SignalIcon.MobileIconGroup, String>> =
        defaultMobileIconMapping
            .mapLatest { networkToIconMap -> networkToIconMap.reverse() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                defaultMobileIconMapping.value.reverse()
            )

    private fun <K, V> Map<K, V>.reverse() = entries.associateBy({ it.value }) { it.key }

    // TODO(b/261029387): add a command for this value
    override val defaultDataSubId = MutableStateFlow(INVALID_SUBSCRIPTION_ID)

    // TODO(b/261029387): not yet supported
    override val mobileIsDefault: StateFlow<Boolean> = MutableStateFlow(true)

    // TODO(b/261029387): not yet supported
    override val hasCarrierMergedConnection = MutableStateFlow(false)

    // TODO(b/261029387): not yet supported
    override val defaultConnectionIsValidated: StateFlow<Boolean> = MutableStateFlow(true)

    override fun getRepoForSubId(subId: Int): DemoMobileConnectionRepository {
        val current = connectionRepoCache[subId]?.repo
        if (current != null) {
            return current
        }

        val new = createDemoMobileConnectionRepo(subId)
        connectionRepoCache[subId] = new
        return new.repo
    }

    private fun createDemoMobileConnectionRepo(subId: Int): CacheContainer {
        val tableLogBuffer =
            logFactory.getOrCreate(
                "DemoMobileConnectionLog[$subId]",
                MOBILE_CONNECTION_BUFFER_SIZE,
            )

        val repo =
            DemoMobileConnectionRepository(
                subId,
                tableLogBuffer,
                scope,
            )
        return CacheContainer(repo, lastMobileState = null)
    }

    fun startProcessingCommands() {
        mobileDemoCommandJob =
            scope.launch {
                mobileDataSource.mobileEvents.filterNotNull().collect { event ->
                    processMobileEvent(event)
                }
            }
        wifiDemoCommandJob =
            scope.launch {
                wifiDataSource.wifiEvents.filterNotNull().collect { event ->
                    processWifiEvent(event)
                }
            }
    }

    fun stopProcessingCommands() {
        mobileDemoCommandJob?.cancel()
        wifiDemoCommandJob?.cancel()
        _subscriptions.value = listOf()
        connectionRepoCache.clear()
        subscriptionInfoCache.clear()
    }

    override suspend fun isInEcmMode(): Boolean = false

    private fun processMobileEvent(event: FakeNetworkEventModel) {
        when (event) {
            is Mobile -> {
                processEnabledMobileState(event)
            }
            is MobileDisabled -> {
                maybeRemoveSubscription(event.subId)
            }
        }
    }

    private fun processWifiEvent(event: FakeWifiEventModel) {
        when (event) {
            is FakeWifiEventModel.WifiDisabled -> disableCarrierMerged()
            is FakeWifiEventModel.Wifi -> disableCarrierMerged()
            is FakeWifiEventModel.CarrierMerged -> processCarrierMergedWifiState(event)
        }
    }

    private fun processEnabledMobileState(event: Mobile) {
        // get or create the connection repo, and set its values
        val subId = event.subId ?: DEFAULT_SUB_ID
        maybeCreateSubscription(subId)

        val connection = getRepoForSubId(subId)
        connectionRepoCache[subId]?.lastMobileState = event

        // TODO(b/261029387): until we have a command, use the most recent subId
        defaultDataSubId.value = subId

        connection.processDemoMobileEvent(event, event.dataType.toResolvedNetworkType())
    }

    private fun processCarrierMergedWifiState(event: FakeWifiEventModel.CarrierMerged) {
        // The new carrier merged connection is for a different sub ID, so disable carrier merged
        // for the current (now old) sub
        if (carrierMergedSubId != event.subscriptionId) {
            disableCarrierMerged()
        }

        // get or create the connection repo, and set its values
        val subId = event.subscriptionId
        maybeCreateSubscription(subId)
        carrierMergedSubId = subId

        // TODO(b/261029387): until we have a command, use the most recent subId
        defaultDataSubId.value = subId

        val connection = getRepoForSubId(subId)
        connection.processCarrierMergedEvent(event)
    }

    private fun maybeRemoveSubscription(subId: Int?) {
        if (_subscriptions.value.isEmpty()) {
            // Nothing to do here
            return
        }

        val finalSubId =
            subId
                ?: run {
                    // For sake of usability, we can allow for no subId arg if there is only one
                    // subscription
                    if (_subscriptions.value.size > 1) {
                        Log.d(
                            TAG,
                            "processDisabledMobileState: Unable to infer subscription to " +
                                "disable. Specify subId using '-e slot <subId>'" +
                                "Known subIds: [${subIdsString()}]"
                        )
                        return
                    }

                    // Use the only existing subscription as our arg, since there is only one
                    _subscriptions.value[0].subscriptionId
                }

        removeSubscription(finalSubId)
    }

    private fun disableCarrierMerged() {
        val currentCarrierMergedSubId = carrierMergedSubId ?: return

        // If this sub ID was previously not carrier merged, we should reset it to its previous
        // connection.
        val lastMobileState = connectionRepoCache[carrierMergedSubId]?.lastMobileState
        if (lastMobileState != null) {
            processEnabledMobileState(lastMobileState)
        } else {
            // Otherwise, just remove the subscription entirely
            removeSubscription(currentCarrierMergedSubId)
        }
    }

    private fun removeSubscription(subId: Int) {
        val currentSubscriptions = _subscriptions.value
        subscriptionInfoCache.remove(subId)
        _subscriptions.value = currentSubscriptions.filter { it.subscriptionId != subId }
    }

    private fun subIdsString(): String =
        _subscriptions.value.joinToString(",") { it.subscriptionId.toString() }

    private fun SignalIcon.MobileIconGroup?.toResolvedNetworkType(): ResolvedNetworkType {
        val key = mobileMappingsReverseLookup.value[this] ?: "dis"
        return DefaultNetworkType(key)
    }

    companion object {
        private const val TAG = "DemoMobileConnectionsRepo"

        private const val DEFAULT_SUB_ID = 1
        private const val DEFAULT_CARRIER_NAME = "demo carrier"
    }
}

class CacheContainer(
    var repo: DemoMobileConnectionRepository,
    /** The last received [Mobile] event. Used when switching from carrier merged back to mobile. */
    var lastMobileState: Mobile?,
)
