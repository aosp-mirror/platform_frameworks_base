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

package com.android.systemui.statusbar.pipeline.wifi.data.repository

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl.Companion.ACTIVITY_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.data.repository.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiRepositoryImpl

    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var wifiManager: WifiManager
    private lateinit var executor: Executor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())

        underTest = WifiRepositoryImpl(
            connectivityManager,
            wifiManager,
            executor,
            logger,
        )
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
        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

        // WHEN we keep the same network ID but change the SSID
        val newSsid = "CD"
        val newWifiInfo = mock<WifiInfo>().apply {
            whenever(this.ssid).thenReturn(newSsid)
            whenever(this.isPrimary).thenReturn(true)
        }

        getNetworkCallback()
            .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(newWifiInfo))

        // THEN we've updated to the new SSID
        assertThat(latest is WifiNetworkModel.Active).isTrue()
        val latestActive = latest as WifiNetworkModel.Active
        assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
        assertThat(latestActive.ssid).isEqualTo(newSsid)

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

    @Test
    fun wifiActivity_nullWifiManager_receivesDefault() = runBlocking(IMMEDIATE) {
        underTest = WifiRepositoryImpl(
                connectivityManager,
                wifiManager = null,
                executor,
                logger,
        )

        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isEqualTo(ACTIVITY_DEFAULT)

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesNone_activityFlowHasNone() = runBlocking(IMMEDIATE) {
        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_NONE)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = false, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesIn_activityFlowHasIn() = runBlocking(IMMEDIATE) {
        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_IN)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesOut_activityFlowHasOut() = runBlocking(IMMEDIATE) {
        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_OUT)

        assertThat(latest).isEqualTo(
            WifiActivityModel(hasActivityIn = false, hasActivityOut = true)
        )

        job.cancel()
    }

    @Test
    fun wifiActivity_callbackGivesInout_activityFlowHasInAndOut() = runBlocking(IMMEDIATE) {
        var latest: WifiActivityModel? = null
        val job = underTest
                .wifiActivity
                .onEach { latest = it }
                .launchIn(this)

        getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT)

        assertThat(latest).isEqualTo(WifiActivityModel(hasActivityIn = true, hasActivityOut = true))

        job.cancel()
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

    private fun createWifiNetworkCapabilities(wifiInfo: WifiInfo) =
        mock<NetworkCapabilities>().apply {
            whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
            whenever(this.transportInfo).thenReturn(wifiInfo)
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
