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
import android.content.Intent
import android.content.IntentFilter
import android.telephony.CarrierConfigManager
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyManager
import android.util.IndentingPrintWriter
import androidx.annotation.VisibleForTesting
import com.android.internal.telephony.PhoneConstants
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.settingslib.mobile.MobileMappings.Config
import com.android.systemui.Dumpable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.logDiffsForTable
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.airplane.data.repository.AirplaneModeRepository
import com.android.systemui.statusbar.pipeline.dagger.MobileSummaryLog
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ServiceStateModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.mobile.util.SubscriptionManagerProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.kotlin.pairwise
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.io.PrintWriter
import java.lang.ref.WeakReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MobileConnectionsRepositoryImpl
@Inject
constructor(
    connectivityRepository: ConnectivityRepository,
    private val subscriptionManager: SubscriptionManager,
    private val subscriptionManagerProxy: SubscriptionManagerProxy,
    private val telephonyManager: TelephonyManager,
    private val logger: MobileInputLogger,
    @MobileSummaryLog private val tableLogger: TableLogBuffer,
    mobileMappingsProxy: MobileMappingsProxy,
    broadcastDispatcher: BroadcastDispatcher,
    private val context: Context,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
    @Main private val mainDispatcher: CoroutineDispatcher,
    airplaneModeRepository: AirplaneModeRepository,
    // Some "wifi networks" should be rendered as a mobile connection, which is why the wifi
    // repository is an input to the mobile repository.
    // See [CarrierMergedConnectionRepository] for details.
    wifiRepository: WifiRepository,
    private val fullMobileRepoFactory: FullMobileConnectionRepository.Factory,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val dumpManager: DumpManager,
) : MobileConnectionsRepository, Dumpable {

    // TODO(b/333912012): for now, we are never invalidating the cache. We can do better though
    private var subIdRepositoryCache:
        MutableMap<Int, WeakReference<FullMobileConnectionRepository>> =
        mutableMapOf()

    private val defaultNetworkName =
        NetworkNameModel.Default(
            context.getString(com.android.internal.R.string.lockscreen_carrier_default)
        )

    private val networkNameSeparator: String =
        context.getString(R.string.status_bar_network_name_separator)

    init {
        dumpManager.registerNormalDumpable("MobileConnectionsRepository", this)
    }

    private val carrierMergedSubId: StateFlow<Int?> =
        combine(
                wifiRepository.wifiNetwork,
                connectivityRepository.defaultConnections,
                airplaneModeRepository.isAirplaneMode,
            ) { wifiNetwork, defaultConnections, isAirplaneMode ->
                // The carrier merged connection should only be used if it's also the default
                // connection or mobile connections aren't available because of airplane mode.
                val defaultConnectionIsNonMobile =
                    defaultConnections.carrierMerged.isDefault ||
                        defaultConnections.wifi.isDefault ||
                        isAirplaneMode

                if (wifiNetwork is WifiNetworkModel.CarrierMerged && defaultConnectionIsNonMobile) {
                    wifiNetwork.subscriptionId
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

    private val mobileSubscriptionsChangeEvent: Flow<Unit> =
        conflatedCallbackFlow {
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
            .flowOn(bgDispatcher)

    /** Note that this flow is eager, so we don't miss any state */
    override val deviceServiceState: StateFlow<ServiceStateModel?> =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(Intent.ACTION_SERVICE_STATE)) { intent, _ ->
                val subId =
                    intent.getIntExtra(
                        SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                        INVALID_SUBSCRIPTION_ID
                    )

                val extras = intent.extras
                if (extras == null) {
                    logger.logTopLevelServiceStateBroadcastMissingExtras(subId)
                    return@broadcastFlow null
                }

                val serviceState = ServiceState.newFromBundle(extras)
                logger.logTopLevelServiceStateBroadcastEmergencyOnly(subId, serviceState)
                if (subId == INVALID_SUBSCRIPTION_ID) {
                    // Assume that -1 here is the device's service state. We don't care about
                    // other ones.
                    ServiceStateModel.fromServiceState(serviceState)
                } else {
                    null
                }
            }
            .filterNotNull()
            .stateIn(scope, SharingStarted.Eagerly, null)

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
            .flowOn(bgDispatcher)
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

    override val defaultDataSubId: StateFlow<Int> =
        broadcastDispatcher
            .broadcastFlow(
                IntentFilter(TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED),
            ) { intent, _ ->
                intent.getIntExtra(PhoneConstants.SUBSCRIPTION_KEY, INVALID_SUBSCRIPTION_ID)
            }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "defaultSubId",
                initialValue = INVALID_SUBSCRIPTION_ID,
            )
            .onStart { emit(subscriptionManagerProxy.getDefaultDataSubscriptionId()) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), INVALID_SUBSCRIPTION_ID)

    private val carrierConfigChangedEvent =
        broadcastDispatcher
            .broadcastFlow(IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED))
            .onEach { logger.logActionCarrierConfigChanged() }

    override val defaultDataSubRatConfig: StateFlow<Config> =
        merge(defaultDataSubId, carrierConfigChangedEvent)
            .onStart { emit(Unit) }
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

    override val isAnySimSecure: Flow<Boolean> =
        conflatedCallbackFlow {
                val callback =
                    object : KeyguardUpdateMonitorCallback() {
                        override fun onSimStateChanged(subId: Int, slotId: Int, simState: Int) {
                            logger.logOnSimStateChanged()
                            trySend(getIsAnySimSecure())
                        }
                    }
                keyguardUpdateMonitor.registerCallback(callback)
                trySend(false)
                awaitClose { keyguardUpdateMonitor.removeCallback(callback) }
            }
            .flowOn(mainDispatcher)
            .logDiffsForTable(
                tableLogger,
                LOGGING_PREFIX,
                columnName = "isAnySimSecure",
                initialValue = false,
            )
            .distinctUntilChanged()

    override fun getIsAnySimSecure() = keyguardUpdateMonitor.isSimPinSecure

    override fun getRepoForSubId(subId: Int): FullMobileConnectionRepository =
        getOrCreateRepoForSubId(subId)

    private fun getOrCreateRepoForSubId(subId: Int) =
        subIdRepositoryCache[subId]?.get()
            ?: createRepositoryForSubId(subId).also {
                subIdRepositoryCache[subId] = WeakReference(it)
            }

    override val mobileIsDefault: StateFlow<Boolean> =
        connectivityRepository.defaultConnections
            .map { it.mobile.isDefault }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = LOGGING_PREFIX,
                columnName = "mobileIsDefault",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val hasCarrierMergedConnection: StateFlow<Boolean> =
        carrierMergedSubId
            .map { it != null }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = LOGGING_PREFIX,
                columnName = "hasCarrierMergedConnection",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val defaultConnectionIsValidated: StateFlow<Boolean> =
        connectivityRepository.defaultConnections
            .map { it.isValidated }
            .distinctUntilChanged()
            .logDiffsForTable(
                tableLogger,
                columnPrefix = "",
                columnName = "defaultConnectionIsValidated",
                initialValue = false,
            )
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

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

    override suspend fun isInEcmMode(): Boolean {
        if (telephonyManager.emergencyCallbackMode) {
            return true
        }
        return with(subscriptions.value) {
            any { getOrCreateRepoForSubId(it.subscriptionId).isInEcmMode() }
        }
    }

    private fun isValidSubId(subId: Int): Boolean = checkSub(subId, subscriptions.value)

    @VisibleForTesting fun getSubIdRepoCache() = subIdRepositoryCache

    private fun subscriptionModelForSubId(subId: Int): Flow<SubscriptionModel?> {
        return subscriptions.map { list ->
            list.firstOrNull { model -> model.subscriptionId == subId }
        }
    }

    private fun createRepositoryForSubId(subId: Int): FullMobileConnectionRepository {
        return fullMobileRepoFactory.build(
            subId,
            isCarrierMerged(subId),
            subscriptionModelForSubId(subId),
            defaultNetworkName,
            networkNameSeparator,
        )
    }

    private fun updateRepos(newInfos: List<SubscriptionModel>) {
        subIdRepositoryCache.forEach { (subId, repo) ->
            repo.get()?.setIsCarrierMerged(isCarrierMerged(subId))
        }
    }

    private fun isCarrierMerged(subId: Int): Boolean {
        return subId == carrierMergedSubId.value
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
            isExclusivelyNonTerrestrial = isOnlyNonTerrestrialNetwork,
            groupUuid = groupUuid,
            carrierName = carrierName.toString(),
            profileClass = profileClass,
        )

    override fun dump(pw: PrintWriter, args: Array<String>) {
        val ipw = IndentingPrintWriter(pw, " ")
        ipw.println("Connection cache:")

        ipw.increaseIndent()
        subIdRepositoryCache.entries.forEach { (subId, repo) ->
            ipw.println("$subId: ${repo.get()}")
        }
        ipw.decreaseIndent()

        ipw.println("Connections (${subIdRepositoryCache.size} total):")
        ipw.increaseIndent()
        subIdRepositoryCache.values.forEach { it.get()?.dump(ipw) }
        ipw.decreaseIndent()
    }

    companion object {
        private const val LOGGING_PREFIX = "Repo"
    }
}
