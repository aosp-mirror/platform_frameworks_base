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

package com.android.systemui.statusbar.pipeline.mobile.data.repository

import android.telephony.CellSignalStrength
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
import android.telephony.TelephonyManager
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.model.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.toDataConnectionType
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.logOutputChange
import java.lang.IllegalStateException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Every mobile line of service can be identified via a [SubscriptionInfo] object. We set up a
 * repository for each individual, tracked subscription via [MobileConnectionsRepository], and this
 * repository is responsible for setting up a [TelephonyManager] object tied to its subscriptionId
 *
 * There should only ever be one [MobileConnectionRepository] per subscription, since
 * [TelephonyManager] limits the number of callbacks that can be registered per process.
 *
 * This repository should have all of the relevant information for a single line of service, which
 * eventually becomes a single icon in the status bar.
 */
interface MobileConnectionRepository {
    /**
     * A flow that aggregates all necessary callbacks from [TelephonyCallback] into a single
     * listener + model.
     */
    val subscriptionModelFlow: Flow<MobileSubscriptionModel>
    /** Observable tracking [TelephonyManager.isDataConnectionAllowed] */
    val dataEnabled: Flow<Boolean>
}

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
class MobileConnectionRepositoryImpl(
    private val subId: Int,
    private val telephonyManager: TelephonyManager,
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

    override val subscriptionModelFlow: StateFlow<MobileSubscriptionModel> = run {
        var state = MobileSubscriptionModel()
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
                                if (
                                    telephonyDisplayInfo.overrideNetworkType ==
                                        OVERRIDE_NETWORK_TYPE_NONE
                                ) {
                                    DefaultNetworkType(telephonyDisplayInfo.networkType)
                                } else {
                                    OverrideNetworkType(telephonyDisplayInfo.overrideNetworkType)
                                }
                            state = state.copy(resolvedNetworkType = networkType)
                            trySend(state)
                        }
                    }
                telephonyManager.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose { telephonyManager.unregisterTelephonyCallback(callback) }
            }
            .logOutputChange(logger, "MobileSubscriptionModel")
            .stateIn(scope, SharingStarted.WhileSubscribed(), state)
    }

    /**
     * There are a few cases where we will need to poll [TelephonyManager] so we can update some
     * internal state where callbacks aren't provided. Any of those events should be merged into
     * this flow, which can be used to trigger the polling.
     */
    private val telephonyPollingEvent: Flow<Unit> = subscriptionModelFlow.map {}

    override val dataEnabled: Flow<Boolean> = telephonyPollingEvent.map { dataConnectionAllowed() }

    private fun dataConnectionAllowed(): Boolean = telephonyManager.isDataConnectionAllowed

    class Factory
    @Inject
    constructor(
        private val telephonyManager: TelephonyManager,
        private val logger: ConnectivityPipelineLogger,
        @Background private val bgDispatcher: CoroutineDispatcher,
        @Application private val scope: CoroutineScope,
    ) {
        fun build(subId: Int): MobileConnectionRepository {
            return MobileConnectionRepositoryImpl(
                subId,
                telephonyManager.createForSubscriptionId(subId),
                bgDispatcher,
                logger,
                scope,
            )
        }
    }
}
