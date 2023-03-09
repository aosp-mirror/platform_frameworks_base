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

import android.content.Context
import android.content.IntentFilter
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.ERI_FLASH
import android.telephony.TelephonyManager.ERI_ON
import android.telephony.TelephonyManager.EXTRA_SUBSCRIPTION_ID
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import com.android.settingslib.Utils
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState.Disconnected
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn

/**
 * A repository implementation for a typical mobile connection (as opposed to a carrier merged
 * connection -- see [CarrierMergedConnectionRepository]).
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionRepositoryImpl(
    private val context: Context,
    override val subId: Int,
    defaultNetworkName: NetworkNameModel,
    networkNameSeparator: String,
    private val telephonyManager: TelephonyManager,
    systemUiCarrierConfig: SystemUiCarrierConfig,
    broadcastDispatcher: BroadcastDispatcher,
    private val mobileMappingsProxy: MobileMappingsProxy,
    bgDispatcher: CoroutineDispatcher,
    logger: MobileInputLogger,
    override val tableLogBuffer: TableLogBuffer,
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

    private val telephonyCallbackEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * This flow defines the single shared connection to system_server via TelephonyCallback. Any
     * new callback should be added to this listener and funneled through callbackEvents via a data
     * class. See [CallbackEvent] for defining new callbacks.
     *
     * The reason we need to do this is because TelephonyManager limits the number of registered
     * listeners per-process, so we don't want to create a new listener for every callback.
     */
    private val callbackEvents: SharedFlow<CallbackEvent> =
        conflatedCallbackFlow {
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
            .shareIn(scope, SharingStarted.WhileSubscribed())

    override val isEmergencyOnly =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnServiceStateChanged>()
            .map { it.serviceState.isEmergencyOnly }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isRoaming =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnServiceStateChanged>()
            .map { it.serviceState.roaming }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val operatorAlphaShort =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnServiceStateChanged>()
            .map { it.serviceState.operatorAlphaShort }
            .stateIn(scope, SharingStarted.WhileSubscribed(), null)

    override val isInService =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnServiceStateChanged>()
            .map { Utils.isInService(it.serviceState) }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val isGsm =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnSignalStrengthChanged>()
            .map { it.signalStrength.isGsm }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val cdmaLevel =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnSignalStrengthChanged>()
            .map {
                it.signalStrength.getCellSignalStrengths(CellSignalStrengthCdma::class.java).let {
                    strengths ->
                    if (strengths.isNotEmpty()) {
                        strengths[0].level
                    } else {
                        CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                    }
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val primaryLevel =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnSignalStrengthChanged>()
            .map { it.signalStrength.level }
            .stateIn(scope, SharingStarted.WhileSubscribed(), SIGNAL_STRENGTH_NONE_OR_UNKNOWN)

    override val dataConnectionState =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnDataConnectionStateChanged>()
            .map { it.dataState.toDataConnectionType() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), Disconnected)

    override val dataActivityDirection =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnDataActivity>()
            .map { it.direction.toMobileDataActivityModel() }
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            )

    override val carrierNetworkChangeActive =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnCarrierNetworkChange>()
            .map { it.active }
            .stateIn(scope, SharingStarted.WhileSubscribed(), false)

    override val resolvedNetworkType =
        callbackEvents
            .filterIsInstance<CallbackEvent.OnDisplayInfoChanged>()
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

    override val networkName: StateFlow<NetworkNameModel> =
        broadcastDispatcher
            .broadcastFlow(
                filter = IntentFilter(TelephonyManager.ACTION_SERVICE_PROVIDERS_UPDATED),
                map = { intent, _ -> intent },
            )
            .filter { intent ->
                intent.getIntExtra(EXTRA_SUBSCRIPTION_ID, INVALID_SUBSCRIPTION_ID) == subId
            }
            .map { intent -> intent.toNetworkNameModel(networkNameSeparator) ?: defaultNetworkName }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultNetworkName)

    override val dataEnabled = run {
        val initial = telephonyManager.isDataConnectionAllowed
        callbackEvents
            .mapNotNull { (it as? CallbackEvent.OnDataEnabledChanged)?.enabled }
            .stateIn(scope, SharingStarted.WhileSubscribed(), initial)
    }

    class Factory
    @Inject
    constructor(
        private val broadcastDispatcher: BroadcastDispatcher,
        private val context: Context,
        private val telephonyManager: TelephonyManager,
        private val logger: MobileInputLogger,
        private val carrierConfigRepository: CarrierConfigRepository,
        private val mobileMappingsProxy: MobileMappingsProxy,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
    ) {
        fun build(
            subId: Int,
            mobileLogger: TableLogBuffer,
            defaultNetworkName: NetworkNameModel,
            networkNameSeparator: String,
        ): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                context,
                subId,
                defaultNetworkName,
                networkNameSeparator,
                telephonyManager.createForSubscriptionId(subId),
                carrierConfigRepository.getOrCreateConfigForSubId(subId),
                broadcastDispatcher,
                mobileMappingsProxy,
                bgDispatcher,
                logger,
                mobileLogger,
                scope,
            )
        }
    }
}

/**
 * Wrap every [TelephonyCallback] we care about in a data class so we can accept them in a single
 * shared flow and then split them back out into other flows.
 */
private sealed interface CallbackEvent {
    data class OnServiceStateChanged(val serviceState: ServiceState) : CallbackEvent
    data class OnSignalStrengthChanged(val signalStrength: SignalStrength) : CallbackEvent
    data class OnDataConnectionStateChanged(val dataState: Int) : CallbackEvent
    data class OnDataActivity(val direction: Int) : CallbackEvent
    data class OnCarrierNetworkChange(val active: Boolean) : CallbackEvent
    data class OnDisplayInfoChanged(val telephonyDisplayInfo: TelephonyDisplayInfo) : CallbackEvent
    data class OnDataEnabledChanged(val enabled: Boolean) : CallbackEvent
}
