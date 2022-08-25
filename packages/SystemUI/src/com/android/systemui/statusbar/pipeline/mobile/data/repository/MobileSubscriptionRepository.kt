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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener
import android.telephony.TelephonyCallback.CarrierNetworkListener
import android.telephony.TelephonyCallback.DataActivityListener
import android.telephony.TelephonyCallback.DataConnectionStateListener
import android.telephony.TelephonyCallback.DisplayInfoListener
import android.telephony.TelephonyCallback.ServiceStateListener
import android.telephony.TelephonyCallback.SignalStrengthsListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.annotation.VisibleForTesting
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Repo for monitoring the complete active subscription info list, to be consumed and filtered based
 * on various policy
 */
interface MobileSubscriptionRepository {
    /** Observable list of current mobile subscriptions */
    val subscriptionsFlow: Flow<List<SubscriptionInfo>>

    /** Observable for the subscriptionId of the current mobile data connection */
    val activeMobileDataSubscriptionId: Flow<Int>

    /** Get or create an observable for the given subscription ID */
    fun getFlowForSubId(subId: Int): Flow<MobileSubscriptionModel>
}

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class MobileSubscriptionRepositoryImpl
@Inject
constructor(
    private val subscriptionManager: SubscriptionManager,
    private val telephonyManager: TelephonyManager,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
) : MobileSubscriptionRepository {
    private val subIdFlowCache: MutableMap<Int, StateFlow<MobileSubscriptionModel>> = mutableMapOf()

    /**
     * State flow that emits the set of mobile data subscriptions, each represented by its own
     * [SubscriptionInfo]. We probably only need the [SubscriptionInfo.getSubscriptionId] of each
     * info object, but for now we keep track of the infos themselves.
     */
    override val subscriptionsFlow: StateFlow<List<SubscriptionInfo>> =
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
            .mapLatest { fetchSubscriptionsList() }
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
            .stateIn(
                scope,
                started = SharingStarted.WhileSubscribed(),
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )

    /**
     * Each mobile subscription needs its own flow, which comes from registering listeners on the
     * system. Use this method to create those flows and cache them for reuse
     */
    override fun getFlowForSubId(subId: Int): StateFlow<MobileSubscriptionModel> {
        return subIdFlowCache[subId]
            ?: createFlowForSubId(subId).also { subIdFlowCache[subId] = it }
    }

    @VisibleForTesting fun getSubIdFlowCache() = subIdFlowCache

    private fun createFlowForSubId(subId: Int): StateFlow<MobileSubscriptionModel> = run {
        var state = MobileSubscriptionModel()
        conflatedCallbackFlow {
                val phony = telephonyManager.createForSubscriptionId(subId)
                // TODO (b/240569788): log all of these into the connectivity logger
                val callback =
                    object :
                        TelephonyCallback(),
                        ServiceStateListener,
                        SignalStrengthsListener,
                        DataConnectionStateListener,
                        DataActivityListener,
                        CarrierNetworkListener,
                        DisplayInfoListener {
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
                            state = state.copy(dataConnectionState = dataState)
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
                            state = state.copy(displayInfo = telephonyDisplayInfo)
                            trySend(state)
                        }
                    }
                phony.registerTelephonyCallback(bgDispatcher.asExecutor(), callback)
                awaitClose {
                    phony.unregisterTelephonyCallback(callback)
                    // Release the cached flow
                    subIdFlowCache.remove(subId)
                }
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(), state)
    }

    private suspend fun fetchSubscriptionsList(): List<SubscriptionInfo> =
        withContext(bgDispatcher) { subscriptionManager.completeActiveSubscriptionInfoList }
}
