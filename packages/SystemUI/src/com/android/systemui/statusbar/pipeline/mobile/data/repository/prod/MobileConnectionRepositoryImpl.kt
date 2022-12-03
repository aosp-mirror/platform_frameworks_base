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
import android.database.ContentObserver
import android.provider.Settings.Global
import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.util.MobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import com.android.systemui.util.settings.GlobalSettings
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionRepositoryImpl(
    private val context: Context,
    override val subId: Int,
    private val telephonyManager: TelephonyManager,
    private val globalSettings: GlobalSettings,
    defaultDataSubId: StateFlow<Int>,
    globalMobileDataSettingChangedEvent: Flow<Unit>,
    mobileMappingsProxy: MobileMappingsProxy,
    bgDispatcher: CoroutineDispatcher,
    logger: ConnectivityPipelineLogger,
    scope: CoroutineScope,
) : MobileConnectionRepository {
    init {
        if (telephonyManager.subscriptionId != subId) {
            throw IllegalStateException(
                "TelephonyManager should be created with subId($subId). " +
                    "Found ${telephonyManager.subscriptionId} instead."
            )
        }
    }

    private val telephonyCallbackEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override val connectionInfo: StateFlow<MobileConnectionModel> = run {
        var state = MobileConnectionModel()
        conflatedCallbackFlow {
                // TODO (b/240569788): log all of these into the connectivity logger
                val callback =
                    object :
                        TelephonyCallback(),
                        TelephonyCallback.ServiceStateListener,
                        TelephonyCallback.SignalStrengthsListener,
                        TelephonyCallback.DataConnectionStateListener,
                        TelephonyCallback.DataActivityListener,
                        TelephonyCallback.CarrierNetworkListener,
                        TelephonyCallback.DisplayInfoListener {
                        override fun onServiceStateChanged(serviceState: ServiceState) {
                            state = state.copy(isEmergencyOnly = serviceState.isEmergencyOnly)
                            trySend(state)
                        }

                        override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                            val cdmaLevel =
                                signalStrength
                                    .getCellSignalStrengths(CellSignalStrengthCdma::class.java)
                                    .let { strengths ->
                                        if (!strengths.isEmpty()) {
                                            strengths[0].level
                                        } else {
                                            CellSignalStrength.SIGNAL_STRENGTH_NONE_OR_UNKNOWN
                                        }
                                    }

                            val primaryLevel = signalStrength.level

                            state =
                                state.copy(
                                    cdmaLevel = cdmaLevel,
                                    primaryLevel = primaryLevel,
                                    isGsm = signalStrength.isGsm,
                                )
                            trySend(state)
                        }

                        override fun onDataConnectionStateChanged(
                            dataState: Int,
                            networkType: Int
                        ) {
                            state =
                                state.copy(dataConnectionState = dataState.toDataConnectionType())
                            trySend(state)
                        }

                        override fun onDataActivity(direction: Int) {
                            state = state.copy(dataActivityDirection = direction)
                            trySend(state)
                        }

                        override fun onCarrierNetworkChange(active: Boolean) {
                            state = state.copy(carrierNetworkChangeActive = active)
                            trySend(state)
                        }

                        override fun onDisplayInfoChanged(
                            telephonyDisplayInfo: TelephonyDisplayInfo
                        ) {

                            val networkType =
                                if (telephonyDisplayInfo.networkType == NETWORK_TYPE_UNKNOWN) {
                                    UnknownNetworkType
                                } else if (
                                    telephonyDisplayInfo.overrideNetworkType ==
                                        OVERRIDE_NETWORK_TYPE_NONE
                                ) {
                                    DefaultNetworkType(
                                        telephonyDisplayInfo.networkType,
                                        mobileMappingsProxy.toIconKey(
                                            telephonyDisplayInfo.networkType
                                        )
                                    )
                                } else {
                                    OverrideNetworkType(
                                        telephonyDisplayInfo.overrideNetworkType,
                                        mobileMappingsProxy.toIconKeyOverride(
                                            telephonyDisplayInfo.overrideNetworkType
                                        )
                                    )
                                }
                            state = state.copy(resolvedNetworkType = networkType)
                            trySend(state)
                        }
                    }
                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .onEach { telephonyCallbackEvent.tryEmit(Unit) }
            .logOutputChange(logger, "MobileSubscriptionModel")
            .stateIn(scope, SharingStarted.WhileSubscribed(), state)
    }

    /** Produces whenever the mobile data setting changes for this subId */
    private val localMobileDataSettingChangedEvent: Flow<Unit> = conflatedCallbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }

        globalSettings.registerContentObserver(
            globalSettings.getUriFor("${Global.MOBILE_DATA}$subId"),
            /* notifyForDescendants */ true,
            observer
        )

        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Flow<Unit> =
        merge(
            telephonyCallbackEvent,
            localMobileDataSettingChangedEvent,
            globalMobileDataSettingChangedEvent,
        )

    override val dataEnabled: StateFlow<Boolean> =
        telephonyPollingEvent
            .mapLatest { dataConnectionAllowed() }
            .stateIn(scope, SharingStarted.WhileSubscribed(), dataConnectionAllowed())

    private fun dataConnectionAllowed(): Boolean = telephonyManager.isDataConnectionAllowed

    override val isDefaultDataSubscription: StateFlow<Boolean> =
        defaultDataSubId
            .mapLatest { it == subId }
            .stateIn(scope, SharingStarted.WhileSubscribed(), defaultDataSubId.value == subId)

    class Factory
    @Inject
    constructor(
        private val context: Context,
        private val telephonyManager: TelephonyManager,
        private val logger: ConnectivityPipelineLogger,
        private val globalSettings: GlobalSettings,
        private val mobileMappingsProxy: MobileMappingsProxy,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
    ) {
        fun build(
            subId: Int,
            defaultDataSubId: StateFlow<Int>,
            globalMobileDataSettingChangedEvent: Flow<Unit>,
        ): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                context,
                subId,
                telephonyManager.createForSubscriptionId(subId),
                globalSettings,
                defaultDataSubId,
                globalMobileDataSettingChangedEvent,
                mobileMappingsProxy,
                bgDispatcher,
                logger,
                scope,
            )
        }
    }
}
