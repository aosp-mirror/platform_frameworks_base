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
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.util.Log
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.Mobile
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.MobileDisabled
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** This repository vends out data based on demo mode commands */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoMobileConnectionsRepository
@Inject
constructor(
    private val dataSource: DemoModeMobileConnectionDataSource,
    @Application private val scope: CoroutineScope,
    context: Context,
) : MobileConnectionsRepository {

    private var demoCommandJob: Job? = null

    private val connectionRepoCache = mutableMapOf<Int, DemoMobileConnectionRepository>()
    private val subscriptionInfoCache = mutableMapOf<Int, SubscriptionInfo>()
    val demoModeFinishedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val _subscriptions = MutableStateFlow<List<SubscriptionInfo>>(listOf())
    override val subscriptionsFlow =
        _subscriptions
            .onEach { infos -> dropUnusedReposFromCache(infos) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), _subscriptions.value)

    private fun dropUnusedReposFromCache(newInfos: List<SubscriptionInfo>) {
        // Remove any connection repository from the cache that isn't in the new set of IDs. They
        // will get garbage collected once their subscribers go away
        val currentValidSubscriptionIds = newInfos.map { it.subscriptionId }

        connectionRepoCache.keys.forEach {
            if (!currentValidSubscriptionIds.contains(it)) {
                connectionRepoCache.remove(it)
            }
        }
    }

    private fun maybeCreateSubscription(subId: Int) {
        if (!subscriptionInfoCache.containsKey(subId)) {
            createSubscriptionForSubId(subId, subId).also { subscriptionInfoCache[subId] = it }

            _subscriptions.value = subscriptionInfoCache.values.toList()
        }
    }

    /** Mimics the old NetworkControllerImpl for now */
    private fun createSubscriptionForSubId(subId: Int, slotIndex: Int): SubscriptionInfo {
        return SubscriptionInfo(
            subId,
            "",
            slotIndex,
            "",
            "",
            0,
            0,
            "",
            0,
            null,
            null,
            null,
            "",
            false,
            null,
            null,
        )
    }

    // TODO(b/261029387): add a command for this value
    override val activeMobileDataSubscriptionId =
        subscriptionsFlow
            .mapLatest { infos ->
                // For now, active is just the first in the list
                infos.firstOrNull()?.subscriptionId ?: INVALID_SUBSCRIPTION_ID
            }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                subscriptionsFlow.value.firstOrNull()?.subscriptionId ?: INVALID_SUBSCRIPTION_ID
            )

    /** Demo mode doesn't currently support modifications to the mobile mappings */
    val defaultDataSubRatConfig = MutableStateFlow(MobileMappings.Config.readConfig(context))

    override val defaultMobileIconGroup = flowOf(TelephonyIcons.THREE_G)

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
    override val defaultDataSubId =
        activeMobileDataSubscriptionId.stateIn(
            scope,
            SharingStarted.WhileSubscribed(),
            INVALID_SUBSCRIPTION_ID
        )

    // TODO(b/261029387): not yet supported
    override val defaultMobileNetworkConnectivity = MutableStateFlow(MobileConnectivityModel())

    override fun getRepoForSubId(subId: Int): DemoMobileConnectionRepository {
        return connectionRepoCache[subId]
            ?: DemoMobileConnectionRepository(subId).also { connectionRepoCache[subId] = it }
    }

    override val globalMobileDataSettingChangedEvent = MutableStateFlow(Unit)

    fun startProcessingCommands() {
        demoCommandJob =
            scope.launch {
                dataSource.mobileEvents.filterNotNull().collect { event -> processEvent(event) }
            }
    }

    fun stopProcessingCommands() {
        demoCommandJob?.cancel()
        _subscriptions.value = listOf()
        connectionRepoCache.clear()
        subscriptionInfoCache.clear()
    }

    private fun processEvent(event: FakeNetworkEventModel) {
        when (event) {
            is Mobile -> {
                processEnabledMobileState(event)
            }
            is MobileDisabled -> {
                processDisabledMobileState(event)
            }
        }
    }

    private fun processEnabledMobileState(state: Mobile) {
        // get or create the connection repo, and set its values
        val subId = state.subId ?: DEFAULT_SUB_ID
        maybeCreateSubscription(subId)

        val connection = getRepoForSubId(subId)
        // This is always true here, because we split out disabled states at the data-source level
        connection.dataEnabled.value = true
        connection.isDefaultDataSubscription.value = state.dataType != null

        connection.subscriptionModelFlow.value = state.toMobileSubscriptionModel()
    }

    private fun processDisabledMobileState(state: MobileDisabled) {
        if (_subscriptions.value.isEmpty()) {
            // Nothing to do here
            return
        }

        val subId =
            state.subId
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

        removeSubscription(subId)
    }

    private fun removeSubscription(subId: Int) {
        val currentSubscriptions = _subscriptions.value
        subscriptionInfoCache.remove(subId)
        _subscriptions.value = currentSubscriptions.filter { it.subscriptionId != subId }
    }

    private fun subIdsString(): String =
        _subscriptions.value.joinToString(",") { it.subscriptionId.toString() }

    private fun Mobile.toMobileSubscriptionModel(): MobileSubscriptionModel {
        return MobileSubscriptionModel(
            isEmergencyOnly = false, // TODO(b/261029387): not yet supported
            isGsm = false, // TODO(b/261029387): not yet supported
            cdmaLevel = level ?: 0,
            primaryLevel = level ?: 0,
            dataConnectionState =
                DataConnectionState.Connected, // TODO(b/261029387): not yet supported
            dataActivityDirection = activity,
            carrierNetworkChangeActive = carrierNetworkChange,
            resolvedNetworkType = dataType.toResolvedNetworkType()
        )
    }

    private fun SignalIcon.MobileIconGroup?.toResolvedNetworkType(): ResolvedNetworkType {
        val key = mobileMappingsReverseLookup.value[this] ?: "dis"
        return DefaultNetworkType(DEMO_NET_TYPE, key)
    }

    companion object {
        private const val TAG = "DemoMobileConnectionsRepo"

        private const val DEFAULT_SUB_ID = 1

        private const val DEMO_NET_TYPE = 1234
    }
}

class DemoMobileConnectionRepository(override val subId: Int) : MobileConnectionRepository {
    override val subscriptionModelFlow = MutableStateFlow(MobileSubscriptionModel())

    override val dataEnabled = MutableStateFlow(true)

    override val isDefaultDataSubscription = MutableStateFlow(true)
}
