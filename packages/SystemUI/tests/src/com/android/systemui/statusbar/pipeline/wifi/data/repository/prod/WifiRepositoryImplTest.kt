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

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.TransportInfo
import android.net.VpnTransportInfo
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.TrafficStateCallback
import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiInputLogger
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiRepositoryImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiRepositoryImpl

    @Mock private lateinit var logger: WifiInputLogger
    @Mock private lateinit var tableLogger: TableLogBuffer
    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var wifiManager: WifiManager
    private lateinit var executor: Executor
    private lateinit var connectivityRepository: ConnectivityRepository

    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        executor = FakeExecutor(FakeSystemClock())

        connectivityRepository =
            ConnectivityRepositoryImpl(
                connectivityManager,
                ConnectivitySlots(context),
                context,
                mock(),
                mock(),
                testScope.backgroundScope,
                mock(),
            )

        underTest = createRepo()
    }

    @Test
    fun isWifiEnabled_initiallyGetsWifiManagerValue() =
        testScope.runTest {
            whenever(wifiManager.isWifiEnabled).thenReturn(true)

            underTest = createRepo()

            assertThat(underTest.isWifiEnabled.value).isTrue()
        }

    @Test
    fun isWifiEnabled_networkCapabilitiesChanged_valueUpdated() =
        testScope.runTest {
            // We need to call launch on the flows so that they start updating
            val networkJob = underTest.wifiNetwork.launchIn(this)
            val enabledJob = underTest.isWifiEnabled.launchIn(this)

            whenever(wifiManager.isWifiEnabled).thenReturn(true)
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

            assertThat(underTest.isWifiEnabled.value).isTrue()

            whenever(wifiManager.isWifiEnabled).thenReturn(false)
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

            assertThat(underTest.isWifiEnabled.value).isFalse()

            networkJob.cancel()
            enabledJob.cancel()
        }

    @Test
    fun isWifiEnabled_networkLost_valueUpdated() =
        testScope.runTest {
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
    fun isWifiEnabled_intentsReceived_valueUpdated() =
        testScope.runTest {
            underTest = createRepo()

            val job = underTest.isWifiEnabled.launchIn(this)

            whenever(wifiManager.isWifiEnabled).thenReturn(true)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION),
            )

            assertThat(underTest.isWifiEnabled.value).isTrue()

            whenever(wifiManager.isWifiEnabled).thenReturn(false)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION),
            )

            assertThat(underTest.isWifiEnabled.value).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiEnabled_bothIntentAndNetworkUpdates_valueAlwaysUpdated() =
        testScope.runTest {
            underTest = createRepo()

            val networkJob = underTest.wifiNetwork.launchIn(this)
            val enabledJob = underTest.isWifiEnabled.launchIn(this)

            whenever(wifiManager.isWifiEnabled).thenReturn(false)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION),
            )
            assertThat(underTest.isWifiEnabled.value).isFalse()

            whenever(wifiManager.isWifiEnabled).thenReturn(true)
            getNetworkCallback().onLost(NETWORK)
            assertThat(underTest.isWifiEnabled.value).isTrue()

            whenever(wifiManager.isWifiEnabled).thenReturn(false)
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
            assertThat(underTest.isWifiEnabled.value).isFalse()

            whenever(wifiManager.isWifiEnabled).thenReturn(true)
            fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
                context,
                Intent(WifiManager.WIFI_STATE_CHANGED_ACTION),
            )
            assertThat(underTest.isWifiEnabled.value).isTrue()

            networkJob.cancel()
            enabledJob.cancel()
        }

    @Test
    fun isWifiDefault_initiallyGetsDefault() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            assertThat(underTest.isWifiDefault.value).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiDefault_wifiNetwork_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val wifiInfo = mock<WifiInfo>().apply { whenever(this.ssid).thenReturn(SSID) }

            getDefaultNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    /** Regression test for b/266628069. */
    @Test
    fun isWifiDefault_transportInfoIsNotWifi_andNoWifiTransport_false() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val transportInfo =
                VpnTransportInfo(
                    /* type= */ 0,
                    /* sessionId= */ "sessionId",
                )
            val networkCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_VPN)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.transportInfo).thenReturn(transportInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, networkCapabilities)

            assertThat(underTest.isWifiDefault.value).isFalse()

            job.cancel()
        }

    /** Regression test for b/266628069. */
    @Test
    fun isWifiDefault_transportInfoIsNotWifi_butHasWifiTransport_true() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val transportInfo =
                VpnTransportInfo(
                    /* type= */ 0,
                    /* sessionId= */ "sessionId",
                )
            val networkCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_VPN)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.transportInfo).thenReturn(transportInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, networkCapabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_carrierMergedViaCellular_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                    whenever(this.transportInfo).thenReturn(carrierMergedInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_carrierMergedViaCellular_withVcnTransport_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                    whenever(this.transportInfo).thenReturn(VcnTransportInfo(PRIMARY_WIFI_INFO))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_carrierMergedViaWifi_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(this.transportInfo).thenReturn(carrierMergedInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_carrierMergedViaWifi_withVcnTransport_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(this.transportInfo).thenReturn(VcnTransportInfo(PRIMARY_WIFI_INFO))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_cellularAndWifiTransports_usesCellular_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(this.transportInfo).thenReturn(VcnTransportInfo(PRIMARY_WIFI_INFO))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_cellularNotVcnNetwork_isFalse() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.transportInfo).thenReturn(mock())
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(underTest.isWifiDefault.value).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiDefault_isCarrierMergedViaUnderlyingWifi_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val underlyingNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
                }
            val underlyingWifiCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingWifiCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via WIFI
            // transport and WifiInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            // THEN the wifi network is carrier merged, so wifi is default
            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_isCarrierMergedViaUnderlyingCellular_isTrue() =
        testScope.runTest {
            val job = underTest.isWifiDefault.launchIn(this)

            val underlyingCarrierMergedNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val underlyingCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingCarrierMergedNetwork))
                .thenReturn(underlyingCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via CELLULAR
            // transport and VcnTransportInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            // THEN the wifi network is carrier merged, so wifi is default
            assertThat(underTest.isWifiDefault.value).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiDefault_wifiNetworkLost_isFalse() =
        testScope.runTest {
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
    fun wifiNetwork_initiallyGetsDefault() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(WIFI_NETWORK_DEFAULT)

            job.cancel()
        }

    @Test
    fun wifiNetwork_primaryWifiNetworkAdded_flowHasNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(SSID)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val network = mock<Network>().apply { whenever(this.getNetId()).thenReturn(NETWORK_ID) }

            getNetworkCallback()
                .onCapabilitiesChanged(network, createWifiNetworkCapabilities(wifiInfo))

            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
            assertThat(latestActive.ssid).isEqualTo(SSID)

            job.cancel()
        }

    @Test
    fun wifiNetwork_isCarrierMerged_flowHasCarrierMerged() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_isCarrierMergedViaUnderlyingWifi_flowHasCarrierMerged() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val underlyingNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val underlyingWifiCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
                .thenReturn(underlyingWifiCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via WIFI
            // transport and WifiInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
                }

            getNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            // THEN the wifi network is carrier merged
            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_isCarrierMergedViaUnderlyingCellular_flowHasCarrierMerged() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val underlyingCarrierMergedNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.isPrimary).thenReturn(true)
                }
            val underlyingCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingCarrierMergedNetwork))
                .thenReturn(underlyingCapabilities)

            // WHEN the main capabilities have an underlying carrier merged network via CELLULAR
            // transport and VcnTransportInfo
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            // THEN the wifi network is carrier merged
            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_carrierMergedButInvalidSubId_flowHasInvalid() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(wifiInfo),
                )

            assertThat(latest).isInstanceOf(WifiNetworkModel.Invalid::class.java)

            job.cancel()
        }

    @Test
    fun wifiNetwork_isCarrierMerged_getsCorrectValues() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val rssi = -57
            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.rssi).thenReturn(rssi)
                    whenever(this.subscriptionId).thenReturn(567)
                }

            whenever(wifiManager.calculateSignalLevel(rssi)).thenReturn(2)
            whenever(wifiManager.maxSignalLevel).thenReturn(5)

            getNetworkCallback()
                .onCapabilitiesChanged(
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
    fun wifiNetwork_notValidated_networkNotValidated() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(PRIMARY_WIFI_INFO, isValidated = false)
                )

            assertThat((latest as WifiNetworkModel.Active).isValidated).isFalse()

            job.cancel()
        }

    @Test
    fun wifiNetwork_validated_networkValidated() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(PRIMARY_WIFI_INFO, isValidated = true)
                )

            assertThat((latest as WifiNetworkModel.Active).isValidated).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_nonPrimaryWifiNetworkAdded_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(SSID)
                    whenever(this.isPrimary).thenReturn(false)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    /** Regression test for b/266628069. */
    @Test
    fun wifiNetwork_transportInfoIsNotWifi_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val transportInfo =
                VpnTransportInfo(
                    /* type= */ 0,
                    /* sessionId= */ "sessionId",
                )
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(transportInfo))

            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_cellularVcnNetworkAdded_flowHasNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
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
    fun wifiNetwork_nonPrimaryCellularVcnNetworkAdded_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(SSID)
                    whenever(this.isPrimary).thenReturn(false)
                }
            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.transportInfo).thenReturn(VcnTransportInfo(wifiInfo))
                }

            getNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_cellularNotVcnNetworkAdded_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.transportInfo).thenReturn(mock())
                }

            getNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_cellularAndWifiTransports_usesCellular() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val capabilities =
                mock<NetworkCapabilities>().apply {
                    whenever(this.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(this.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
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
    fun wifiNetwork_newPrimaryWifiNetwork_flowHasNewNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            // Start with the original network
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

            // WHEN we update to a new primary network
            val newNetworkId = 456
            val newNetwork =
                mock<Network>().apply { whenever(this.getNetId()).thenReturn(newNetworkId) }
            val newSsid = "CD"
            val newWifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(newSsid)
                    whenever(this.isPrimary).thenReturn(true)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(newNetwork, createWifiNetworkCapabilities(newWifiInfo))

            // THEN we use the new network
            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.networkId).isEqualTo(newNetworkId)
            assertThat(latestActive.ssid).isEqualTo(newSsid)

            job.cancel()
        }

    @Test
    fun wifiNetwork_newNonPrimaryWifiNetwork_flowHasOldNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            // Start with the original network
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

            // WHEN we notify of a new but non-primary network
            val newNetworkId = 456
            val newNetwork =
                mock<Network>().apply { whenever(this.getNetId()).thenReturn(newNetworkId) }
            val newSsid = "EF"
            val newWifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(newSsid)
                    whenever(this.isPrimary).thenReturn(false)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(newNetwork, createWifiNetworkCapabilities(newWifiInfo))

            // THEN we still use the original network
            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
            assertThat(latestActive.ssid).isEqualTo(SSID)

            job.cancel()
        }

    @Test
    fun wifiNetwork_newNetworkCapabilities_flowHasNewData() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(SSID)
                    whenever(this.isPrimary).thenReturn(true)
                }

            // Start with the original network
            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(wifiInfo, isValidated = true)
                )

            // WHEN we keep the same network ID but change the SSID
            val newSsid = "CD"
            val newWifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(newSsid)
                    whenever(this.isPrimary).thenReturn(true)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(newWifiInfo, isValidated = false)
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
    fun wifiNetwork_noCurrentNetwork_networkLost_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            // WHEN we receive #onLost without any #onCapabilitiesChanged beforehand
            getNetworkCallback().onLost(NETWORK)

            // THEN there's no crash and we still have no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_currentActiveNetworkLost_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
            assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

            // WHEN we lose our current network
            getNetworkCallback().onLost(NETWORK)

            // THEN we update to no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    /** Possible regression test for b/278618530. */
    @Test
    fun wifiNetwork_currentCarrierMergedNetworkLost_flowHasNoNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))
            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            assertThat((latest as WifiNetworkModel.CarrierMerged).networkId).isEqualTo(NETWORK_ID)

            // WHEN we lose our current network
            getNetworkCallback().onLost(NETWORK)

            // THEN we update to no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_unknownNetworkLost_flowHasPreviousNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
            assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

            // WHEN we lose an unknown network
            val unknownNetwork = mock<Network>().apply { whenever(this.getNetId()).thenReturn(543) }
            getNetworkCallback().onLost(unknownNetwork)

            // THEN we still have our previous network
            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.networkId).isEqualTo(NETWORK_ID)
            assertThat(latestActive.ssid).isEqualTo(SSID)

            job.cancel()
        }

    @Test
    fun wifiNetwork_notCurrentNetworkLost_flowHasCurrentNetwork() =
        testScope.runTest {
            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
            assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(NETWORK_ID)

            // WHEN we update to a new network...
            val newNetworkId = 89
            val newNetwork =
                mock<Network>().apply { whenever(this.getNetId()).thenReturn(newNetworkId) }
            getNetworkCallback()
                .onCapabilitiesChanged(newNetwork, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))
            // ...and lose the old network
            getNetworkCallback().onLost(NETWORK)

            // THEN we still have the new network
            assertThat((latest as WifiNetworkModel.Active).networkId).isEqualTo(newNetworkId)

            job.cancel()
        }

    /** Regression test for b/244173280. */
    @Test
    fun wifiNetwork_multipleSubscribers_newSubscribersGetCurrentValue() =
        testScope.runTest {
            var latest1: WifiNetworkModel? = null
            val job1 = underTest.wifiNetwork.onEach { latest1 = it }.launchIn(this)

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(PRIMARY_WIFI_INFO))

            assertThat(latest1 is WifiNetworkModel.Active).isTrue()
            val latest1Active = latest1 as WifiNetworkModel.Active
            assertThat(latest1Active.networkId).isEqualTo(NETWORK_ID)
            assertThat(latest1Active.ssid).isEqualTo(SSID)

            // WHEN we add a second subscriber after having already emitted a value
            var latest2: WifiNetworkModel? = null
            val job2 = underTest.wifiNetwork.onEach { latest2 = it }.launchIn(this)

            // THEN the second subscribe receives the already-emitted value
            assertThat(latest2 is WifiNetworkModel.Active).isTrue()
            val latest2Active = latest2 as WifiNetworkModel.Active
            assertThat(latest2Active.networkId).isEqualTo(NETWORK_ID)
            assertThat(latest2Active.ssid).isEqualTo(SSID)

            job1.cancel()
            job2.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_inactiveNetwork_false() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.ssid).thenReturn(SSID)
                    // A non-primary network is inactive
                    whenever(this.isPrimary).thenReturn(false)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_carrierMergedNetwork_false() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_invalidNetwork_false() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(wifiInfo),
                )

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_nullSsid_false() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.ssid).thenReturn(null)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_unknownSsid_false() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.ssid).thenReturn(UNKNOWN_SSID)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_validSsid_true() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.ssid).thenReturn("FakeSsid")
                }

            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))

            assertThat(underTest.isWifiConnectedWithValidSsid()).isTrue()

            job.cancel()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeToInactive_trueToFalse() =
        testScope.runTest {
            val job = underTest.wifiNetwork.launchIn(this)

            // Start with active
            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.ssid).thenReturn("FakeSsid")
                }
            getNetworkCallback()
                .onCapabilitiesChanged(NETWORK, createWifiNetworkCapabilities(wifiInfo))
            assertThat(underTest.isWifiConnectedWithValidSsid()).isTrue()

            // WHEN the network is lost
            getNetworkCallback().onLost(NETWORK)

            // THEN the isWifiConnected updates
            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()

            job.cancel()
        }

    @Test
    fun wifiActivity_callbackGivesNone_activityFlowHasNone() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.wifiActivity.onEach { latest = it }.launchIn(this)

            getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_NONE)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))

            job.cancel()
        }

    @Test
    fun wifiActivity_callbackGivesIn_activityFlowHasIn() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.wifiActivity.onEach { latest = it }.launchIn(this)

            getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_IN)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = false))

            job.cancel()
        }

    @Test
    fun wifiActivity_callbackGivesOut_activityFlowHasOut() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.wifiActivity.onEach { latest = it }.launchIn(this)

            getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_OUT)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = true))

            job.cancel()
        }

    @Test
    fun wifiActivity_callbackGivesInout_activityFlowHasInAndOut() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.wifiActivity.onEach { latest = it }.launchIn(this)

            getTrafficStateCallback().onStateChanged(TrafficStateCallback.DATA_ACTIVITY_INOUT)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = true))

            job.cancel()
        }

    private fun createRepo(): WifiRepositoryImpl {
        return WifiRepositoryImpl(
            fakeBroadcastDispatcher,
            connectivityManager,
            connectivityRepository,
            logger,
            tableLogger,
            executor,
            testScope.backgroundScope,
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
        transportInfo: TransportInfo,
        isValidated: Boolean = true,
    ): NetworkCapabilities {
        return mock<NetworkCapabilities>().also {
            whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
            whenever(it.transportInfo).thenReturn(transportInfo)
            whenever(it.hasCapability(NET_CAPABILITY_VALIDATED)).thenReturn(isValidated)
        }
    }

    private companion object {
        const val NETWORK_ID = 45
        val NETWORK = mock<Network>().apply { whenever(this.getNetId()).thenReturn(NETWORK_ID) }
        const val SSID = "AB"
        val PRIMARY_WIFI_INFO: WifiInfo =
            mock<WifiInfo>().apply {
                whenever(this.ssid).thenReturn(SSID)
                whenever(this.isPrimary).thenReturn(true)
            }
    }
}
