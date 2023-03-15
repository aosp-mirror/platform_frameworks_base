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
import android.telephony.TelephonyManager.DATA_ACTIVITY_NONE
import android.util.Log
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.MobileMappings
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectivityModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.Mobile
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.MobileDisabled
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
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
    private val logFactory: TableLogBufferFactory,
) : MobileConnectionsRepository {

    private var demoCommandJob: Job? = null

    private var connectionRepoCache = mutableMapOf<Int, DemoMobileConnectionRepository>()
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
            SubscriptionModel(subscriptionId = subId, isOpportunistic = false).also {
                subscriptionInfoCache[subId] = it
            }

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

    /** Demo mode doesn't currently support modifications to the mobile mappings */
    override val defaultDataSubRatConfig =
        MutableStateFlow(MobileMappings.Config.readConfig(context))

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

    // TODO(b/261029387): not yet supported
    override val defaultMobileNetworkConnectivity = MutableStateFlow(MobileConnectivityModel())

    override fun getRepoForSubId(subId: Int): DemoMobileConnectionRepository {
        return connectionRepoCache[subId]
            ?: createDemoMobileConnectionRepo(subId).also { connectionRepoCache[subId] = it }
    }

    private fun createDemoMobileConnectionRepo(subId: Int): DemoMobileConnectionRepository {
        val tableLogBuffer = logFactory.create("DemoMobileConnectionLog [$subId]", 100)

        return DemoMobileConnectionRepository(
            subId,
            tableLogBuffer,
        )
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
        connection.networkName.value = NetworkNameModel.Derived(state.name)

        connection.cdmaRoaming.value = state.roaming
        connection.connectionInfo.value = state.toMobileConnectionModel()
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

    private fun Mobile.toMobileConnectionModel(): MobileConnectionModel {
        return MobileConnectionModel(
            isEmergencyOnly = false, // TODO(b/261029387): not yet supported
            isRoaming = roaming,
            isInService = (level ?: 0) > 0,
            isGsm = false, // TODO(b/261029387): not yet supported
            cdmaLevel = level ?: 0,
            primaryLevel = level ?: 0,
            dataConnectionState =
                DataConnectionState.Connected, // TODO(b/261029387): not yet supported
            dataActivityDirection = (activity ?: DATA_ACTIVITY_NONE).toMobileDataActivityModel(),
            carrierNetworkChangeActive = carrierNetworkChange,
            resolvedNetworkType = dataType.toResolvedNetworkType()
        )
    }

    private fun SignalIcon.MobileIconGroup?.toResolvedNetworkType(): ResolvedNetworkType {
        val key = mobileMappingsReverseLookup.value[this] ?: "dis"
        return DefaultNetworkType(key)
    }

    companion object {
        private const val TAG = "DemoMobileConnectionsRepo"

        private const val DEFAULT_SUB_ID = 1
    }
}

class DemoMobileConnectionRepository(
    override val subId: Int,
    override val tableLogBuffer: TableLogBuffer,
) : MobileConnectionRepository {
    override val connectionInfo = MutableStateFlow(MobileConnectionModel())

    override val numberOfLevels = MutableStateFlow(DEFAULT_NUM_LEVELS)

    override val dataEnabled = MutableStateFlow(true)

    override val cdmaRoaming = MutableStateFlow(false)

    override val networkName = MutableStateFlow(NetworkNameModel.Derived("demo network"))
}
