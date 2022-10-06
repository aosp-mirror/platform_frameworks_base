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
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.statusbar.pipeline.mobile.data.model.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileSubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileSubscriptionRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: MobileSubscriptionRepositoryImpl

    @Mock private lateinit var subscriptionManager: SubscriptionManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    private val scope = CoroutineScope(IMMEDIATE)
    private val mobileMappings = FakeMobileMappingsProxy()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(
                broadcastDispatcher.broadcastFlow(
                    any(),
                    nullable(),
                    ArgumentMatchers.anyInt(),
                    nullable(),
                )
            )
            .thenReturn(flowOf(Unit))

        underTest =
            MobileSubscriptionRepositoryImpl(
                subscriptionManager,
                telephonyManager,
                logger,
                broadcastDispatcher,
                context,
                mobileMappings,
                IMMEDIATE,
                scope,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testSubscriptions_initiallyEmpty() =
        runBlocking(IMMEDIATE) {
            assertThat(underTest.subscriptionsFlow.value).isEqualTo(listOf<SubscriptionInfo>())
        }

    @Test
    fun testSubscriptions_listUpdates() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionInfo>? = null

            val job = underTest.subscriptionsFlow.onEach { latest = it }.launchIn(this)

            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            assertThat(latest).isEqualTo(listOf(SUB_1, SUB_2))

            job.cancel()
        }

    @Test
    fun testSubscriptions_removingSub_updatesList() =
        runBlocking(IMMEDIATE) {
            var latest: List<SubscriptionInfo>? = null

            val job = underTest.subscriptionsFlow.onEach { latest = it }.launchIn(this)

            // WHEN 2 networks show up
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_1, SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // WHEN one network is removed
            whenever(subscriptionManager.completeActiveSubscriptionInfoList)
                .thenReturn(listOf(SUB_2))
            getSubscriptionCallback().onSubscriptionsChanged()

            // THEN the subscriptions list represents the newest change
            assertThat(latest).isEqualTo(listOf(SUB_2))

            job.cancel()
        }

    @Test
    fun testActiveDataSubscriptionId_initialValueIsInvalidId() =
        runBlocking(IMMEDIATE) {
            assertThat(underTest.activeMobileDataSubscriptionId.value)
                .isEqualTo(SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        }

    @Test
    fun testActiveDataSubscriptionId_updates() =
        runBlocking(IMMEDIATE) {
            var active: Int? = null

            val job = underTest.activeMobileDataSubscriptionId.onEach { active = it }.launchIn(this)

            getActiveDataSubscriptionCallback().onActiveDataSubscriptionIdChanged(SUB_2_ID)

            assertThat(active).isEqualTo(SUB_2_ID)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_default() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(MobileSubscriptionModel())

            job.cancel()
        }

    @Test
    fun testFlowForSubId_emergencyOnly() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val serviceState = ServiceState()
            serviceState.isEmergencyOnly = true

            getTelephonyCallbackForType<ServiceStateListener>().onServiceStateChanged(serviceState)

            assertThat(latest?.isEmergencyOnly).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_emergencyOnly_toggles() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<ServiceStateListener>()
            val serviceState = ServiceState()
            serviceState.isEmergencyOnly = true
            callback.onServiceStateChanged(serviceState)
            serviceState.isEmergencyOnly = false
            callback.onServiceStateChanged(serviceState)

            assertThat(latest?.isEmergencyOnly).isEqualTo(false)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_signalStrengths_levelsUpdate() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<SignalStrengthsListener>()
            val strength = signalStrength(1, 2, true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest?.isGsm).isEqualTo(true)
            assertThat(latest?.primaryLevel).isEqualTo(1)
            assertThat(latest?.cdmaLevel).isEqualTo(2)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(100, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(100)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataActivity() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<DataActivityListener>()
            callback.onDataActivity(3)

            assertThat(latest?.dataActivityDirection).isEqualTo(3)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_carrierNetworkChange() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<CarrierNetworkListener>()
            callback.onCarrierNetworkChange(true)

            assertThat(latest?.carrierNetworkChangeActive).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_defaultNetworkType() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val type = NETWORK_TYPE_UNKNOWN
            val expected = DefaultNetworkType(type)

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_networkTypeUpdates_default() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<DisplayInfoListener>()
            val type = NETWORK_TYPE_LTE
            val expected = DefaultNetworkType(type)
            val ti = mock<TelephonyDisplayInfo>().also { whenever(it.networkType).thenReturn(type) }
            callback.onDisplayInfoChanged(ti)

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_networkTypeUpdates_override() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<DisplayInfoListener>()
            val type = OVERRIDE_NETWORK_TYPE_LTE_CA
            val expected = OverrideNetworkType(type)
            val ti =
                mock<TelephonyDisplayInfo>().also {
                    whenever(it.overrideNetworkType).thenReturn(type)
                }
            callback.onDisplayInfoChanged(ti)

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_isCached() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            val state1 = underTest.getFlowForSubId(SUB_1_ID)
            val state2 = underTest.getFlowForSubId(SUB_1_ID)

            assertThat(state1).isEqualTo(state2)
        }

    @Test
    fun testFlowForSubId_isRemovedAfterFinish() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.createForSubscriptionId(any())).thenReturn(telephonyManager)

            var latest: MobileSubscriptionModel? = null

            // Start collecting on some flow
            val job = underTest.getFlowForSubId(SUB_1_ID).onEach { latest = it }.launchIn(this)

            // There should be once cached flow now
            assertThat(underTest.getSubIdFlowCache().size).isEqualTo(1)

            // When the job is canceled, the cache should be cleared
            job.cancel()

            assertThat(underTest.getSubIdFlowCache().size).isEqualTo(0)
        }

    private fun getSubscriptionCallback(): SubscriptionManager.OnSubscriptionsChangedListener {
        val callbackCaptor = argumentCaptor<SubscriptionManager.OnSubscriptionsChangedListener>()
        verify(subscriptionManager)
            .addOnSubscriptionsChangedListener(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun getActiveDataSubscriptionCallback(): ActiveDataSubscriptionIdListener =
        getTelephonyCallbackForType()

    private fun getTelephonyCallbacks(): List<TelephonyCallback> {
        val callbackCaptor = argumentCaptor<TelephonyCallback>()
        verify(telephonyManager).registerTelephonyCallback(any(), callbackCaptor.capture())
        return callbackCaptor.allValues
    }

    private inline fun <reified T> getTelephonyCallbackForType(): T {
        val cbs = getTelephonyCallbacks().filterIsInstance<T>()
        assertThat(cbs.size).isEqualTo(1)
        return cbs[0]
    }

    /** Convenience constructor for SignalStrength */
    private fun signalStrength(gsmLevel: Int, cdmaLevel: Int, isGsm: Boolean): SignalStrength {
        val signalStrength = mock<SignalStrength>()
        whenever(signalStrength.isGsm).thenReturn(isGsm)
        whenever(signalStrength.level).thenReturn(gsmLevel)
        val cdmaStrength =
            mock<CellSignalStrengthCdma>().also { whenever(it.level).thenReturn(cdmaLevel) }
        whenever(signalStrength.getCellSignalStrengths(CellSignalStrengthCdma::class.java))
            .thenReturn(listOf(cdmaStrength))

        return signalStrength
    }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
        private const val SUB_1_ID = 1
        private val SUB_1 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_1_ID) }

        private const val SUB_2_ID = 2
        private val SUB_2 =
            mock<SubscriptionInfo>().also { whenever(it.subscriptionId).thenReturn(SUB_2_ID) }
    }
}
