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

import android.os.UserHandle
import android.provider.Settings
import android.telephony.CellSignalStrengthCdma
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.ServiceStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.DATA_CONNECTED
import android.telephony.TelephonyManager.DATA_CONNECTING
import android.telephony.TelephonyManager.DATA_DISCONNECTED
import android.telephony.TelephonyManager.DATA_DISCONNECTING
import android.telephony.TelephonyManager.DATA_UNKNOWN
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.DefaultNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.OverrideNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType.UnknownNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileConnectionRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: MobileConnectionRepositoryImpl

    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: ConnectivityPipelineLogger

    private val scope = CoroutineScope(IMMEDIATE)
    private val mobileMappings = FakeMobileMappingsProxy()
    private val globalSettings = FakeSettings()
    private val connectionsRepo = FakeMobileConnectionsRepository(mobileMappings)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        globalSettings.userId = UserHandle.USER_ALL
        whenever(telephonyManager.subscriptionId).thenReturn(SUB_1_ID)

        underTest =
            MobileConnectionRepositoryImpl(
                context,
                SUB_1_ID,
                telephonyManager,
                globalSettings,
                connectionsRepo.defaultDataSubId,
                connectionsRepo.globalMobileDataSettingChangedEvent,
                mobileMappings,
                IMMEDIATE,
                logger,
                scope,
            )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun testFlowForSubId_default() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(MobileConnectionModel())

            job.cancel()
        }

    @Test
    fun testFlowForSubId_emergencyOnly() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val serviceState = ServiceState()
            serviceState.isEmergencyOnly = true

            getTelephonyCallbackForType<ServiceStateListener>().onServiceStateChanged(serviceState)

            assertThat(latest?.isEmergencyOnly).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_emergencyOnly_toggles() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

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
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>()
            val strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            assertThat(latest?.isGsm).isEqualTo(true)
            assertThat(latest?.primaryLevel).isEqualTo(1)
            assertThat(latest?.cdmaLevel).isEqualTo(2)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState_connected() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_CONNECTED, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(DataConnectionState.Connected)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState_connecting() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_CONNECTING, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(DataConnectionState.Connecting)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState_disconnected() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_DISCONNECTED, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(DataConnectionState.Disconnected)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState_disconnecting() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_DISCONNECTING, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(DataConnectionState.Disconnecting)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataConnectionState_unknown() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback =
                getTelephonyCallbackForType<TelephonyCallback.DataConnectionStateListener>()
            callback.onDataConnectionStateChanged(DATA_UNKNOWN, 200 /* unused */)

            assertThat(latest?.dataConnectionState).isEqualTo(DataConnectionState.Unknown)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_dataActivity() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DataActivityListener>()
            callback.onDataActivity(3)

            assertThat(latest?.dataActivityDirection).isEqualTo(3)

            job.cancel()
        }

    @Test
    fun testFlowForSubId_carrierNetworkChange() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.CarrierNetworkListener>()
            callback.onCarrierNetworkChange(true)

            assertThat(latest?.carrierNetworkChangeActive).isEqualTo(true)

            job.cancel()
        }

    @Test
    fun subscriptionFlow_networkType_default() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val type = NETWORK_TYPE_UNKNOWN
            val expected = UnknownNetworkType

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun subscriptionFlow_networkType_updatesUsingDefault() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val type = NETWORK_TYPE_LTE
            val expected = DefaultNetworkType(type, mobileMappings.toIconKey(type))
            val ti = mock<TelephonyDisplayInfo>().also { whenever(it.networkType).thenReturn(type) }
            callback.onDisplayInfoChanged(ti)

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun subscriptionFlow_networkType_updatesUsingOverride() =
        runBlocking(IMMEDIATE) {
            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val callback = getTelephonyCallbackForType<TelephonyCallback.DisplayInfoListener>()
            val type = OVERRIDE_NETWORK_TYPE_LTE_CA
            val expected = OverrideNetworkType(type, mobileMappings.toIconKeyOverride(type))
            val ti =
                mock<TelephonyDisplayInfo>().also {
                    whenever(it.networkType).thenReturn(type)
                    whenever(it.overrideNetworkType).thenReturn(type)
                }
            callback.onDisplayInfoChanged(ti)

            assertThat(latest?.resolvedNetworkType).isEqualTo(expected)

            job.cancel()
        }

    @Test
    fun dataEnabled_initial_false() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(true)

            assertThat(underTest.dataEnabled.value).isFalse()
        }

    @Test
    fun dataEnabled_isEnabled_true() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(true)
            val job = underTest.dataEnabled.launchIn(this)

            assertThat(underTest.dataEnabled.value).isTrue()

            job.cancel()
        }

    @Test
    fun dataEnabled_isDisabled() =
        runBlocking(IMMEDIATE) {
            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(false)
            val job = underTest.dataEnabled.launchIn(this)

            assertThat(underTest.dataEnabled.value).isFalse()

            job.cancel()
        }

    @Test
    fun isDefaultDataSubscription_isDefault() =
        runBlocking(IMMEDIATE) {
            connectionsRepo.setDefaultDataSubId(SUB_1_ID)

            var latest: Boolean? = null
            val job = underTest.isDefaultDataSubscription.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isDefaultDataSubscription_isNotDefault() =
        runBlocking(IMMEDIATE) {
            // Our subId is SUB_1_ID
            connectionsRepo.setDefaultDataSubId(123)

            var latest: Boolean? = null
            val job = underTest.isDefaultDataSubscription.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun isDataConnectionAllowed_subIdSettingUpdate_valueUpdated() =
        runBlocking(IMMEDIATE) {
            val subIdSettingName = "${Settings.Global.MOBILE_DATA}$SUB_1_ID"

            var latest: Boolean? = null
            val job = underTest.dataEnabled.onEach { latest = it }.launchIn(this)

            // We don't read the setting directly, we query telephony when changes happen
            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(false)
            globalSettings.putInt(subIdSettingName, 0)
            assertThat(latest).isFalse()

            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(true)
            globalSettings.putInt(subIdSettingName, 1)
            assertThat(latest).isTrue()

            whenever(telephonyManager.isDataConnectionAllowed).thenReturn(false)
            globalSettings.putInt(subIdSettingName, 0)
            assertThat(latest).isFalse()

            job.cancel()
        }

    private fun getTelephonyCallbacks(): List<TelephonyCallback> {
        val callbackCaptor = argumentCaptor<TelephonyCallback>()
        Mockito.verify(telephonyManager).registerTelephonyCallback(any(), callbackCaptor.capture())
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
    }
}
