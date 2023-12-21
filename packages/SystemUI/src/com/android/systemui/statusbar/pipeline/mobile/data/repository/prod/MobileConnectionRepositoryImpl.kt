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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ERI_FLASH
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toNetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.CarrierConfigRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository.Companion.DEFAULT_NUM_LEVELS
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * A repository implementation for a typical mobile connection (as opposed to a carrier merged
 * connection -- see [CarrierMergedConnectionRepository]).
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionRepositoryImpl(
    override val subId: Int,
    private val context: Context,
    subscriptionModel: Flow<SubscriptionModel?>,
    defaultNetworkName: NetworkNameModel,
    networkNameSeparator: String,
    connectivityManager: ConnectivityManager,
    private val telephonyManager: TelephonyManager,
    systemUiCarrierConfig: SystemUiCarrierConfig,
    broadcastDispatcher: BroadcastDispatcher,
    private val mobileMappingsProxy: MobileMappingsProxy,
    private val bgDispatcher: CoroutineDispatcher,
    logger: MobileInputLogger,
    override val tableLogBuffer: TableLogBuffer,
    flags: FeatureFlagsClassic,
    scope: CoroutineScope,
) : MobileConnectionRepository {
    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "MobileRepo: TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }

    /**
     * This flow defines the single shared connection to system_server via TelephonyCallback. Any
     * new callback should be added to this listener and funneled through callbackEvents via a data
     * class. See [CallbackEvent] for defining new callbacks.
     *
     * The reason we need to do this is because TelephonyManager limits the number of registered
     * listeners per-process, so we don't want to create a new listener for every callback.
     *
     * A note on the design for back pressure here: We don't control _which_ telephony callback
     * comes in first, since we register every relevant bit of information as a batch. E.g., if a
     * downstream starts collecting on a field which is backed by
     * [TelephonyCallback.ServiceStateListener], it's not possible for us to guarantee that _that_
     * callback comes in -- the first callback could very well be
     * [TelephonyCallback.DataActivityListener], which would promptly be dropped if we didn't keep
     * it tracked. We use the [scan] operator here to track the most recent callback of _each type_
     * here. See [TelephonyCallbackState] to see how the callbacks are stored.
     */
    private val callbackEvents: StateFlow<TelephonyCallbackState> = run {
        val initial = TelephonyCallbackState()
        callbackFlow {
                val callback =
                    object :
                        TelephonyCallback(),
                        TelephonyCallback.ServiceStateListener,
                        TelephonyCallback.SignalStrengthsListener,
                        TelephonyCallback.DataConnectionStateListener,
                        TelephonyCallback.DataActivityListener,
                        TelephonyCallback.CarrierNetworkListener,
                        TelephonyCallback.DisplayInfoListener,
                        TelephonyCallback.DataEnabledListener {
                        override fun onServiceStateChanged(serviceState: ServiceState) {
                            logger.logOnServiceStateChanged(serviceState, subId)
                            trySend(CallbackEvent.OnServiceStateChanged(serviceState))
                        }

                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            logger.logOnSignalStrengthsChanged(signalStrength, subId)
                            trySend(CallbackEvent.OnSignalStrengthChanged(signalStrength))
                        }

                        override fun onDataConnectionStateChanged(
                            dataState: Int,
                            networkType: Int
                        ) {
                            logger.logOnDataConnectionStateChanged(dataState, networkType, subId)
                            trySend(CallbackEvent.OnDataConnectionStateChanged(dataState))
                        }

                        override fun onDataActivity(direction: Int) {
                            logger.logOnDataActivity(direction, subId)
                            trySend(CallbackEvent.OnDataActivity(direction))
                        }

                        override fun onCarrierNetworkChange(active: Boolean) {
                            logger.logOnCarrierNetworkChange(active, subId)
                            trySend(CallbackEvent.OnCarrierNetworkChange(active))
                        }

                        override fun onDisplayInfoChanged(
                            telephonyDisplayInfo: TelephonyDisplayInfo
                        ) {
                            logger.logOnDisplayInfoChanged(telephonyDisplayInfo, subId)
                            trySend(CallbackEvent.OnDisplayInfoChanged(telephonyDisplayInfo))
                        }

                        override fun onDataEnabledChanged(enabled: Boolean, reason: Int) {
                            logger.logOnDataEnabledChanged(enabled, subId)
                            trySend(CallbackEvent.OnDataEnabledChanged(enabled))
                        }
                    }
                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .flowOn(bgDispatcher)
            .scan(initial = initial) { state, event -> state.applyEvent(event) }
            .stateIn(scope = scope, started = SharingStarted.WhileSubscribed(), initial)
    }

    override val isEmergencyOnly =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isRoaming =
        if (flags.isEnabled(ROAMING_INDICATOR_VIA_DISPLAY_INFO)) {
                callbackEvents
                    .mapNotNull { it.onDisplayInfoChanged }
                    .map { it.telephonyDisplayInfo.isRoaming }
            } else {
                callbackEvents
                    .mapNotNull { it.onServiceStateChanged }
                    .map { it.serviceState.roaming }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val operatorAlphaShort =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { it.serviceState.operatorAlphaShort }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val isInService =
        callbackEvents
            .mapNotNull { it.onServiceStateChanged }
            .map { Utils.isInService(it.serviceState) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isGsm =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.isGsm }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val cdmaLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthCdma::class.java).let {
                    strengths ->
                    if (strengths.isNotEmpty()) {
                        strengths[0].level
                    } else {
                        SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val primaryLevel =
        callbackEvents
            .mapNotNull { it.onSignalStrengthChanged }
            .map { it.signalStrength.level }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val dataConnectionState =
        callbackEvents
            .mapNotNull { it.onDataConnectionStateChanged }
            .map { it.dataState.toDataConnectionType() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), Disconnected)

    override val dataActivityDirection =
        callbackEvents
            .mapNotNull { it.onDataActivity }
            .map { it.direction.toMobileDataActivityModel() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            )

    override val carrierNetworkChangeActive =
        callbackEvents
            .mapNotNull { it.onCarrierNetworkChange }
            .map { it.active }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val resolvedNetworkType =
        callbackEvents
            .mapNotNull { it.onDisplayInfoChanged }
            .map {
                if (it.telephonyDisplayInfo.overrideNetworkType != OVERRIDE_NETWORK_TYPE_NONE) {
                    OverrideNetworkType(
                        mobileMappingsProxy.toIconKeyOverride(
                            it.telephonyDisplayInfo.overrideNetworkType
                        )
                    )
                } else if (it.telephonyDisplayInfo.networkType != NETWORK_TYPE_UNKNOWN) {
                    DefaultNetworkType(
                        mobileMappingsProxy.toIconKey(it.telephonyDisplayInfo.networkType)
                    )
                } else {
                    UnknownNetworkType
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), UnknownNetworkType)

    override val numberOfLevels =
        systemUiCarrierConfig.shouldInflateSignalStrength
            .map { shouldInflate ->
                if (shouldInflate) {
                    DEFAULT_NUM_LEVELS + 1
                } else {
                    DEFAULT_NUM_LEVELS
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), DEFAULT_NUM_LEVELS)

    override val carrierName =
        subscriptionModel
            .map {
                it?.let { model -> NetworkNameModel.SubscriptionDerived(model.carrierName) }
                    ?: defaultNetworkName
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Flow<Unit> = callbackEvents.map { Unit }

    override val cdmaRoaming: StateFlow<Boolean> =
        telephonyPollingEvent
            .mapLatest {
                val cdmaEri = telephonyManager.cdmaEnhancedRoamingIndicatorDisplayNumber
                cdmaEri == ERI_ON || cdmaEri == ERI_FLASH
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val carrierId =
        broadcastDispatcher
            .broadcastFlow(
                filter =
                    IntentFilter(TelephonyManager.ACTION_SUBSCRIPTION_CARRIER_IDENTITY_CHANGED),
                map = { intent, _ -> intent },
            )
            .filter { intent ->
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, INVALID_SUBSCRIPTION_ID) == subId
            }
            .map { it.carrierId() }
            .onStart {
                // Make sure we get the initial carrierId
                emit(telephonyManager.simCarrierId)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), telephonyManager.simCarrierId)

    /** BroadcastDispatcher does not handle sticky broadcasts, so we can't use it here */
    @SuppressLint("RegisterReceiverViaContext")
    override val networkName: StateFlow<NetworkNameModel> =
        conflatedCallbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (
                                intent.getIntExtra(
                                    EXTRA_SUBSCRIPTION_INDEX,
                                    INVALID_SUBSCRIPTION_ID
                                ) == subId
                            ) {
                                logger.logServiceProvidersUpdatedBroadcast(intent)
                                trySend(
                                    intent.toNetworkNameModel(networkNameSeparator)
                                        ?: defaultNetworkName
                                )
                            }
                        }
                    }

                context.registerReceiver(
                    receiver,
                    IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED)
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    override val dataEnabled = run {
        val initial = telephonyManager.isDataConnectionAllowed
        callbackEvents
            .mapNotNull { it.onDataEnabledChanged }
            .map { it.enabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    override suspend fun isInEcmMode(): Boolean =
        withContext(bgDispatcher) { telephonyManager.emergencyCallbackMode }

    /** Typical mobile connections aren't available during airplane mode. */
    override val isAllowedDuringAirplaneMode = MutableStateFlow(false).asStateFlow()

    /**
     * Currently, a network with NET_CAPABILITY_PRIORITIZE_LATENCY is the only type of network that
     * we consider to be a "network slice". _PRIORITIZE_BANDWIDTH may be added in the future. Any of
     * these capabilities that are used here must also be represented in the
     * self_certified_network_capabilities.xml config file
     */
    @SuppressLint("WrongConstant")
    private val networkSliceRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
            .setSubscriptionIds(setOf(subId))
            .build()

    @SuppressLint("MissingPermission")
    override val hasPrioritizedNetworkCapabilities: StateFlow<Boolean> =
        conflatedCallbackFlow {
                // Our network callback listens only for this.subId && net_cap_prioritize_latency
                // therefore our state is a simple mapping of whether or not that network exists
                val callback =
                    object : NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            logger.logPrioritizedNetworkAvailable(network.netId)
                            trySend(true)
                        }

                        override fun onLost(network: Network) {
                            logger.logPrioritizedNetworkLost(network.netId)
                            trySend(false)
                        }
                    }

                connectivityManager.registerNetworkCallback(networkSliceRequest, callback)

                awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
            }
            .flowOn(bgDispatcher)
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    class Factory
    @Inject
    constructor(
        private val context: Context,
        private val broadcastDispatcher: BroadcastDispatcher,
        private val connectivityManager: ConnectivityManager,
        private val telephonyManager: TelephonyManager,
        private val logger: MobileInputLogger,
        private val carrierConfigRepository: CarrierConfigRepository,
        private val mobileMappingsProxy: MobileMappingsProxy,
        private val flags: FeatureFlagsClassic,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            subscriptionModel: Flow<SubscriptionModel?>,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
        ): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                subId,
                context,
                subscriptionModel,
                defaultNetworkName,
                networkNameSeparator,
                connectivityManager,
                telephonyManager.createForSubscriptionId(subId),
                carrierConfigRepository.getOrCreateConfigForSubId(subId),
                broadcastDispatcher,
                mobileMappingsProxy,
                bgDispatcher,
                logger,
                mobileLogger,
                flags,
                scope,
            )
        }
    }
}

private fun Intent.carrierId(): Int =
    getIntExtra(TelephonyManager.EXTRA_CARRIER_ID, UNKNOWN_CARRIER_ID)

/**
 * Wrap every [TelephonyCallback] we care about in a data class so we can accept them in a single
 * shared flow and then split them back out into other flows.
 */
sealed interface CallbackEvent {
    data class OnCarrierNetworkChange(val active: Boolean) : CallbackEvent

    data class OnDataActivity(val direction: Int) : CallbackEvent

    data class OnDataConnectionStateChanged(val dataState: Int) : CallbackEvent

    data class OnDataEnabledChanged(val enabled: Boolean) : CallbackEvent

    data class OnDisplayInfoChanged(val telephonyDisplayInfo: TelephonyDisplayInfo) : CallbackEvent

    data class OnServiceStateChanged(val serviceState: ServiceState) : CallbackEvent

    data class OnSignalStrengthChanged(val signalStrength: SignalStrength) : CallbackEvent
}

/**
 * A simple box type for 1-to-1 mapping of [CallbackEvent] to the batched event. Used in conjunction
 * with [scan] to make sure we don't drop important callbacks due to late subscribers
 */
data class TelephonyCallbackState(
    val onDataActivity: CallbackEvent.OnDataActivity? = null,
    val onCarrierNetworkChange: CallbackEvent.OnCarrierNetworkChange? = null,
    val onDataConnectionStateChanged: CallbackEvent.OnDataConnectionStateChanged? = null,
    val onDataEnabledChanged: CallbackEvent.OnDataEnabledChanged? = null,
    val onDisplayInfoChanged: CallbackEvent.OnDisplayInfoChanged? = null,
    val onServiceStateChanged: CallbackEvent.OnServiceStateChanged? = null,
    val onSignalStrengthChanged: CallbackEvent.OnSignalStrengthChanged? = null,
) {
    fun applyEvent(event: CallbackEvent): TelephonyCallbackState {
        return when (event) {
            is CallbackEvent.OnCarrierNetworkChange -> copy(onCarrierNetworkChange = event)
            is CallbackEvent.OnDataActivity -> copy(onDataActivity = event)
            is CallbackEvent.OnDataConnectionStateChanged ->
                copy(onDataConnectionStateChanged = event)
            is CallbackEvent.OnDataEnabledChanged -> copy(onDataEnabledChanged = event)
            is CallbackEvent.OnDisplayInfoChanged -> copy(onDisplayInfoChanged = event)
            is CallbackEvent.OnServiceStateChanged -> {
                copy(onServiceStateChanged = event)
            }
            is CallbackEvent.OnSignalStrengthChanged -> copy(onSignalStrengthChanged = event)
        }
    }
}
