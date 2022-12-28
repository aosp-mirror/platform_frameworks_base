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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.prod

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiRepositoryImpl

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var tableLogger: TableLogBuffer
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var wifiManager: WifiManager
    private lateinit var executor: Executor
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(
            broadcastDispatcher.broadcastFlow(
                any(),
                nullable(),
                anyInt(),
                nullable(),
            )
        ).thenReturn(flowOf(Unit))
        executor = FakeExecutor(FakeSystemClock())
        scope = CoroutineScope(IMMEDIATE)
        underTest = createRepo()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun isWifiEnabled_initiallyGetsWifiManagerValue() = runBlocking(IMMEDIATE) {
        whenever(wifiManager.isWifiEnabled).thenReturn(true)

        underTest = createRepo()

        assertThat(underTest.isWifiEnabled.value).isTrue()
    }

    @Test
    fun isWifiEnabled_networkCapabilitiesChanged_valueUpdated() = runBlocking(IMMEDIATE) {
        // We need to call launch on the flows so that they start updating
        val networkJob = underTest.wifiNetwork.launchIn(this)
        val enabledJob = underTest.isWifiEnabled.launchIn(this)

        whenever(wifiManager.isWifiEnabled).thenReturn(true)
        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO)
        )

        assertThat(underTest.isWifiEnabled.value).isTrue()

        whenever(wifiManager.isWifiEnabled).thenReturn(false)
        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO)
        )

        assertThat(underTest.isWifiEnabled.value).isFalse()

        networkJob.cancel()
        enabledJob.cancel()
    }

    @Test
    fun isWifiEnabled_networkLost_valueUpdated() = runBlocking(IMMEDIATE) {
        // We need to call launch on the flows so that they start updating
        val networkJob = underTest.wifiNetwork.launchIn(this)
        val enabledJob = underTest.isWifiEnabled.launchIn(this)

        whenever(wifiManager.isWifiEnabled).thenReturn(true)
        getNetworkCallback().onLost(NETWORK)

        assertThat(underTest.isWifiEnabled.value).isTrue()

        whenever(wifiManager.isWifiEnabled).thenReturn(false)
        getNetworkCallback().onLost(NETWORK)

        assertThat(underTest.isWifiEnabled.value).isFalse()

        networkJob.cancel()
        enabledJob.cancel()
    }

    @Test
    fun isWifiEnabled_intentsReceived_valueUpdated() = runBlocking(IMMEDIATE) {
        val intentFlow = MutableSharedFlow<Unit>()
        whenever(
            broadcastDispatcher.broadcastFlow(
                any(),
                nullable(),
                anyInt(),
                nullable(),
            )
        ).thenReturn(intentFlow)
        underTest = createRepo()

        val job = underTest.isWifiEnabled.launchIn(this)

        whenever(wifiManager.isWifiEnabled).thenReturn(true)
        intentFlow.emit(Unit)

        assertThat(underTest.isWifiEnabled.value).isTrue()

        whenever(wifiManager.isWifiEnabled).thenReturn(false)
        intentFlow.emit(Unit)

        assertThat(underTest.isWifiEnabled.value).isFalse()

        job.cancel()
    }

    @Test
    fun isWifiEnabled_bothIntentAndNetworkUpdates_valueAlwaysUpdated() = runBlocking(IMMEDIATE) {
        val intentFlow = MutableSharedFlow<Unit>()
        whenever(
            broadcastDispatcher.broadcastFlow(
                any(),
                nullable(),
                anyInt(),
                nullable(),
            )
        ).thenReturn(intentFlow)
        underTest = createRepo()

        val networkJob = underTest.wifiNetwork.launchIn(this)
        val enabledJob = underTest.isWifiEnabled.launchIn(this)

        whenever(wifiManager.isWifiEnabled).thenReturn(false)
        intentFlow.emit(Unit)
        assertThat(underTest.isWifiEnabled.value).isFalse()

        whenever(wifiManager.isWifiEnabled).thenReturn(true)
        getNetworkCallback().onLost(NETWORK)
        assertThat(underTest.isWifiEnabled.value).isTrue()

        whenever(wifiManager.isWifiEnabled).thenReturn(false)
        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO)
        )
        assertThat(underTest.isWifiEnabled.value).isFalse()

        whenever(wifiManager.isWifiEnabled).thenReturn(true)
        intentFlow.emit(Unit)
        assertThat(underTest.isWifiEnabled.value).isTrue()

        networkJob.cancel()
        enabledJob.cancel()
    }

    @Test
    fun isWifiDefault_initiallyGetsDefault() = runBlocking(IMMEDIATE) {
        val job = underTest.isWifiDefault.launchIn(this)

        assertThat(underTest.isWifiDefault.value).isFalse()

        job.cancel()
    }

    @Test
    fun isWifiDefault_wifiNetwork_isTrue() = runBlocking(IMMEDIATE) {
        val job = underTest.isWifiDefault.launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
        }

        getDefaultNetworkCallback().onCapabilitiesChanged(
            NETWORK,
            createWifiNetworkCapabilities(wifiInfo)
        )

        assertThat(underTest.isWifiDefault.value).isTrue()

        job.cancel()
    }

    @Test
    fun isWifiDefault_cellularVcnNetwork_isTrue() = runBlocking(IMMEDIATE) {
        val job = underTest.isWifiDefault.launchIn(this)

        val capabilities = mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(VcnTransportInfo(PRIMARY_WIFI_INFO))
        }

        getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

        assertThat(underTest.isWifiDefault.value).isTrue()

        job.cancel()
    }

    @Test
    fun isWifiDefault_cellularNotVcnNetwork_isFalse() = runBlocking(IMMEDIATE) {
        val job = underTest.isWifiDefault.launchIn(this)

        val capabilities = mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(mock())
        }

        getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

        assertThat(underTest.isWifiDefault.value).isFalse()

        job.cancel()
    }

    @Test
    fun isWifiDefault_wifiNetworkLost_isFalse() = runBlocking(IMMEDIATE) {
        val job = underTest.isWifiDefault.launchIn(this)

        // First, add a network
        getDefaultNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
        assertThat(underTest.isWifiDefault.value).isTrue()

        // WHEN the network is lost
        getDefaultNetworkCallback().onLost(NETWORK)

        // THEN we update to false
        assertThat(underTest.isWifiDefault.value).isFalse()

        job.cancel()
    }

    @Test
    fun wifiNetwork_initiallyGetsDefault() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isEqualTo(WIFI_NETWORK_DEFAULT)

        job.cancel()
    }

    @Test
    fun wifiNetwork_primaryWifiNetworkAdded_flowHasNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
            whenever(this.isPrimary).thenReturn(true)
        }
        val network = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(NETWORK_ID)
        }

        getNetworkCallback().onCapabilitiesChanged(network, createWifiNetworkCapabilities(wifiInfo))

        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(SSID)

        job.cancel()
    }

    @Test
    fun wifiNetwork_isCarrierMerged_flowHasCarrierMerged() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.isPrimary).thenReturn(true)
            whenever(this.isCarrierMerged).thenReturn(true)
        }

        getNetworkCallback().onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

        assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_carrierMergedButInvalidSubId_flowHasInvalid() =
        runBlocking(IMMEDIATE) {
            var latest: WifiNetworkModel? = null
            val job = underTest
                .wifiNetwork
                .onEach { latest = it }
                .launchIn(this)

            val wifiInfo = mock<WifiInfo>().apply {
                whenever(this.isPrimary).thenReturn(true)
                whenever(this.isCarrierMerged).thenReturn(true)
                whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
            }

            getNetworkCallback().onCapabilitiesChanged(
                NETWORK,
                createWifiNetworkCapabilities(wifiInfo),
            )

            assertThat(latest).isInstanceOf(WifiNetworkModel.Invalid::class.java)

            job.cancel()
        }

    @Test
    fun wifiNetwork_isCarrierMerged_getsCorrectValues() =
        runBlocking(IMMEDIATE) {
            var latest: WifiNetworkModel? = null
            val job = underTest
                .wifiNetwork
                .onEach { latest = it }
                .launchIn(this)

            val rssi = -57
            val wifiInfo = mock<WifiInfo>().apply {
                whenever(this.isPrimary).thenReturn(true)
                whenever(this.isCarrierMerged).thenReturn(true)
                whenever(this.rssi).thenReturn(rssi)
                whenever(this.subscriptionId).thenReturn(567)
            }

            whenever(wifiManager.calculateSignalLevel(rssi)).thenReturn(2)
            whenever(wifiManager.maxSignalLevel).thenReturn(5)

            getNetworkCallback().onCapabilitiesChanged(
                NETWORK,
                createWifiNetworkCapabilities(wifiInfo),
            )

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            val latestCarrierMerged = latest as WifiNetworkModel.CarrierMerged
            assertThat(latestCarrierMerged.networkId).isEqualTo(NETWORK_ID)
            assertThat(latestCarrierMerged.subscriptionId).isEqualTo(567)
            assertThat(latestCarrierMerged.level).isEqualTo(2)
            // numberOfLevels = maxSignalLevel + 1
            assertThat(latestCarrierMerged.numberOfLevels).isEqualTo(6)

            job.cancel()
        }

    @Test
    fun wifiNetwork_notValidated_networkNotValidated() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO, isValidated = false)
        )

        assertThat((latest as WifiNetworkModel.Active).isValidated).isFalse()

        job.cancel()
    }

    @Test
    fun wifiNetwork_validated_networkValidated() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO, isValidated = true)
        )

        assertThat((latest as WifiNetworkModel.Active).isValidated).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_nonPrimaryWifiNetworkAdded_flowHasNoNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
            whenever(this.isPrimary).thenReturn(false)
        }

        getNetworkCallback().onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

        assertThat(latest is WifiNetworkModel.Inactive).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_cellularVcnNetworkAdded_flowHasNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val capabilities = mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(VcnTransportInfo(PRIMARY_WIFI_INFO))
        }

        getNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(SSID)

        job.cancel()
    }

    @Test
    fun wifiNetwork_nonPrimaryCellularVcnNetworkAdded_flowHasNoNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
            whenever(this.isPrimary).thenReturn(false)
        }
        val capabilities = mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(VcnTransportInfo(wifiInfo))
        }

        getNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

        assertThat(latest is WifiNetworkModel.Inactive).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_cellularNotVcnNetworkAdded_flowHasNoNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val capabilities = mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(mock())
        }

        getNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

        assertThat(latest is WifiNetworkModel.Inactive).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_newPrimaryWifiNetwork_flowHasNewNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        // Start with the original network
        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

        // WHEN we update to a new primary network
        val newNetworkId = 456
        val newNetwork = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(newNetworkId)
        }
        val newSsid = "CD"
        val newWifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(newSsid)
            whenever(this.isPrimary).thenReturn(true)
        }

        getNetworkCallback().onCapabilitiesChanged(
            newNetwork, createWifiNetworkCapabilities(newWifiInfo)
        )

        // THEN we use the new network
        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(newNetworkId)
        assertThat(latestActive.ssid).isEqualTo(newSsid)

        job.cancel()
    }

    @Test
    fun wifiNetwork_newNonPrimaryWifiNetwork_flowHasOldNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        // Start with the original network
        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

        // WHEN we notify of a new but non-primary network
        val newNetworkId = 456
        val newNetwork = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(newNetworkId)
        }
        val newSsid = "EF"
        val newWifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(newSsid)
            whenever(this.isPrimary).thenReturn(false)
        }

        getNetworkCallback().onCapabilitiesChanged(
            newNetwork, createWifiNetworkCapabilities(newWifiInfo)
        )

        // THEN we still use the original network
        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(SSID)

        job.cancel()
    }

    @Test
    fun wifiNetwork_newNetworkCapabilities_flowHasNewData() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        val wifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
            whenever(this.isPrimary).thenReturn(true)
        }

        // Start with the original network
        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(wifiInfo, isValidated = true)
        )

        // WHEN we keep the same network ID but change the SSID
        val newSsid = "CD"
        val newWifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(newSsid)
            whenever(this.isPrimary).thenReturn(true)
        }

        getNetworkCallback().onCapabilitiesChanged(
            NETWORK, createWifiNetworkCapabilities(newWifiInfo, isValidated = false)
        )

        // THEN we've updated to the new SSID
        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(newSsid)
        assertThat(latestActive.isValidated).isFalse()

        job.cancel()
    }

    @Test
    fun wifiNetwork_noCurrentNetwork_networkLost_flowHasNoNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        // WHEN we receive #onLost without any #onCapabilitiesChanged beforehand
        getNetworkCallback().onLost(NETWORK)

        // THEN there's no crash and we still have no network
        assertThat(latest is WifiNetworkModel.Inactive).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_currentNetworkLost_flowHasNoNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
        assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

        // WHEN we lose our current network
        getNetworkCallback().onLost(NETWORK)

        // THEN we update to no network
        assertThat(latest is WifiNetworkModel.Inactive).isTrue()

        job.cancel()
    }

    @Test
    fun wifiNetwork_unknownNetworkLost_flowHasPreviousNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
        assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

        // WHEN we lose an unknown network
        val unknownNetwork = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(543)
        }
        getNetworkCallback().onLost(unknownNetwork)

        // THEN we still have our previous network
        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(SSID)

        job.cancel()
    }

    @Test
    fun wifiNetwork_notCurrentNetworkLost_flowHasCurrentNetwork() = runBlocking(IMMEDIATE) {
        var latest: WifiNetworkModel? = null
        val job = underTest
            .wifiNetwork
            .onEach { latest = it }
            .launchIn(this)

        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
        assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

        // WHEN we update to a new network...
        val newNetworkId = 89
        val newNetwork = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(newNetworkId)
        }
        getNetworkCallback().onCapabilitiesChanged(
            newNetwork, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO)
        )
        // ...and lose the old network
        getNetworkCallback().onLost(NETWORK)

        // THEN we still have the new network
        assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(newNetworkId)

        job.cancel()
    }

    /** Regression test for b/244173280. */
    @Test
    fun wifiNetwork_multipleSubscribers_newSubscribersGetCurrentValue() = runBlocking(IMMEDIATE) {
        var latest1: WifiNetworkModel? = null
        val job1 = underTest
            .wifiNetwork
            .onEach { latest1 = it }
            .launchIn(this)

        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

        assertThat(latest1 is WifiNetworkModel.Active).isTrue()
        val latest1Active = latest1 as WifiNetworkModel.Active
        assertThat(latest1Active.networkId).isEqualTo(NETWORK_ID)
        assertThat(latest1Active.ssid).isEqualTo(SSID)

        // WHEN we add a second subscriber after having already emitted a value
        var latest2: WifiNetworkModel? = null
        val job2 = underTest
            .wifiNetwork
            .onEach { latest2 = it }
            .launchIn(this)

        // THEN the second subscribe receives the already-emitted value
        assertThat(latest2 is WifiNetworkModel.Active).isTrue()
        val latest2Active = latest2 as WifiNetworkModel.Active
        assertThat(latest2Active.networkId).isEqualTo(NETWORK_ID)
        assertThat(latest2Active.ssid).isEqualTo(SSID)

        job1.cancel()
        job2.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesNone_activityFlowHasNone() = runBlocking(IMMEDIATE) {
        var latest: DataActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_NONE)

        assertThat(latest).isEqualTo(
            DataActivityModel(hasActivityIn = false, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesIn_activityFlowHasIn() = runBlocking(IMMEDIATE) {
        var latest: DataActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_IN)

        assertThat(latest).isEqualTo(
            DataActivityModel(hasActivityIn = true, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesOut_activityFlowHasOut() = runBlocking(IMMEDIATE) {
        var latest: DataActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_OUT)

        assertThat(latest).isEqualTo(
            DataActivityModel(hasActivityIn = false, hasActivityOut = true)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesInout_activityFlowHasInAndOut() = runBlocking(IMMEDIATE) {
        var latest: DataActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT)

        assertThat(latest).isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = true))

        job.cancel()
    }

    private fun createRepo(): WifiRepositoryImpl {
        return WifiRepositoryImpl(
            broadcastDispatcher,
            connectivityManager,
            logger,
            tableLogger,
            executor,
            scope,
            wifiManager,
        )
    }

    private fun getTrafficStateCallback(): TrafficStateCallback {
        val callbackCaptor = argumentCaptor<TrafficStateCallback>()
        verify(wifiManager).registerTrafficStateCallback(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun getNetworkCallback(): ConnectivityManager.NetworkCallback {
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(connectivityManager).registerNetworkCallback(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun getDefaultNetworkCallback(): ConnectivityManager.NetworkCallback {
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun createWifiNetworkCapabilities(
        wifiInfo: WifiInfo,
        isValidated: Boolean = true,
    ): NetworkCapabilities {
        return mock<NetworkCapabilities>().also {
            whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
            whenever(it.transportInfo).thenReturn(wifiInfo)
            whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(isValidated)
        }
    }

    private companion object {
        const val NETWORK_ID = 45
        val NETWORK = mock<Network>().apply {
            whenever(this.getNetId()).thenReturn(NETWORK_ID)
        }
        const val SSID = "AB"
        val PRIMARY_WIFI_INFO: WifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(SSID)
            whenever(this.isPrimary).thenReturn(true)
        }
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
