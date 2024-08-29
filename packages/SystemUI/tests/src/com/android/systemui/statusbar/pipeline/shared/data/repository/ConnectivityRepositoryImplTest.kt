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

package com.android.systemui.statusbar.pipeline.shared.data.repository

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.VpnTransportInfo
import android.net.vcn.VcnTransportInfo
import android.net.wifi.WifiInfo
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags.FLAG_STATUS_BAR_ALWAYS_CHECK_UNDERLYING_NETWORKS
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.dumpManager
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.statusbar.pipeline.shared.ConnectivityInputLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlots
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.DEFAULT_HIDDEN_ICONS_RESOURCE
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.HIDDEN_ICONS_TUNABLE_KEY
import com.android.systemui.statusbar.pipeline.shared.data.repository.ConnectivityRepositoryImpl.Companion.getMainOrUnderlyingWifiInfo
import com.android.systemui.testKosmos
import com.android.systemui.tuner.TunerService
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ConnectivityRepositoryImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: ConnectivityRepositoryImpl

    private val connectivityManager = mock<ConnectivityManager>()
    private val connectivitySlots = mock<ConnectivitySlots>()
    private val dumpManager = kosmos.dumpManager
    private val logger = ConnectivityInputLogger(FakeLogBuffer.Factory.create())
    private val testScope = kosmos.testScope
    private val tunerService = mock<TunerService>()

    @Before
    fun setUp() {
        createAndSetRepo()
    }

    @Test
    fun forceHiddenSlots_initiallyGetsDefault() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()
            context
                .getOrCreateTestableResources()
                .addOverride(DEFAULT_HIDDEN_ICONS_RESOURCE, arrayOf(SLOT_WIFI, SLOT_ETHERNET))
            // Re-create our [ConnectivityRepositoryImpl], since it fetches
            // config_statusBarIconsToExclude when it's first constructed
            createAndSetRepo()

            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            assertThat(latest).containsExactly(ConnectivitySlot.ETHERNET, ConnectivitySlot.WIFI)
        }

    @Test
    fun forceHiddenSlots_slotNamesAdded_flowHasSlots() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()

            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)

            assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)
        }

    @Test
    fun forceHiddenSlots_wrongKey_doesNotUpdate() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()

            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)

            // WHEN onTuningChanged with the wrong key
            getTunable().onTuningChanged("wrongKey", SLOT_WIFI)

            // THEN we didn't update our value and still have the old one
            assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)
        }

    @Test
    fun forceHiddenSlots_slotNamesAddedThenNull_flowHasDefault() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()
            context
                .getOrCreateTestableResources()
                .addOverride(DEFAULT_HIDDEN_ICONS_RESOURCE, arrayOf(SLOT_WIFI, SLOT_ETHERNET))
            // Re-create our [ConnectivityRepositoryImpl], since it fetches
            // config_statusBarIconsToExclude when it's first constructed
            createAndSetRepo()

            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            // First, update the slots
            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, SLOT_MOBILE)
            assertThat(latest).containsExactly(ConnectivitySlot.MOBILE)

            // WHEN we update to a null value
            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, null)

            // THEN we go back to our default value
            assertThat(latest).containsExactly(ConnectivitySlot.ETHERNET, ConnectivitySlot.WIFI)
        }

    @Test
    fun forceHiddenSlots_someInvalidSlotNames_flowHasValidSlotsOnly() =
        testScope.runTest {
            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            whenever(connectivitySlots.getSlotFromName(SLOT_WIFI)).thenReturn(ConnectivitySlot.WIFI)
            whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE)).thenReturn(null)

            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_WIFI,$SLOT_MOBILE")

            assertThat(latest).containsExactly(ConnectivitySlot.WIFI)
        }

    @Test
    fun forceHiddenSlots_someEmptySlotNames_flowHasValidSlotsOnly() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()

            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            // WHEN there's empty and blank slot names
            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_MOBILE,  ,,$SLOT_WIFI")

            // THEN we skip that slot but still process the other ones
            assertThat(latest).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.MOBILE)
        }

    @Test
    fun forceHiddenSlots_allInvalidOrEmptySlotNames_flowHasEmpty() =
        testScope.runTest {
            val latest by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            whenever(connectivitySlots.getSlotFromName(SLOT_WIFI)).thenReturn(null)
            whenever(connectivitySlots.getSlotFromName(SLOT_ETHERNET)).thenReturn(null)
            whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE)).thenReturn(null)

            getTunable()
                .onTuningChanged(
                    HIDDEN_ICONS_TUNABLE_KEY,
                    "$SLOT_MOBILE,,$SLOT_WIFI,$SLOT_ETHERNET,,,"
                )

            assertThat(latest).isEmpty()
        }

    @Test
    fun forceHiddenSlots_newSubscriberGetsCurrentValue() =
        testScope.runTest {
            setUpEthernetWifiMobileSlotNames()

            val latest1 by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            getTunable().onTuningChanged(HIDDEN_ICONS_TUNABLE_KEY, "$SLOT_WIFI,$SLOT_ETHERNET")

            assertThat(latest1).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.ETHERNET)

            // WHEN we add a second subscriber after having already emitted a value
            val latest2 by collectLastValue(underTest.forceHiddenSlots)
            runCurrent()

            // THEN the second subscribe receives the already-emitted value
            assertThat(latest2).containsExactly(ConnectivitySlot.WIFI, ConnectivitySlot.ETHERNET)
        }

    @Test
    fun defaultConnections_noTransports_nothingIsDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.mobile.isDefault).isFalse()
            assertThat(latest!!.wifi.isDefault).isFalse()
            assertThat(latest!!.ethernet.isDefault).isFalse()
            assertThat(latest!!.carrierMerged.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_cellularTransport_mobileIsDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isFalse()
            assertThat(latest!!.ethernet.isDefault).isFalse()
            assertThat(latest!!.carrierMerged.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_wifiTransport_wifiIsDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.wifi.isDefault).isTrue()
            assertThat(latest!!.ethernet.isDefault).isFalse()
            assertThat(latest!!.carrierMerged.isDefault).isFalse()
            assertThat(latest!!.mobile.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_ethernetTransport_ethernetIsDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.ethernet.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isFalse()
            assertThat(latest!!.carrierMerged.isDefault).isFalse()
            assertThat(latest!!.mobile.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_carrierMergedViaWifi_wifiAndCarrierMergedDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.wifi.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.mobile.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_carrierMergedViaMobile_mobileCarrierMergedWifiDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isTrue()
        }

    @Test
    fun defaultConnections_carrierMergedViaWifiWithVcnTransport_wifiAndCarrierMergedDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.wifi.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.mobile.isDefault).isFalse()
        }

    /** VCN over W+ (aka VCN over carrier merged). See b/352162710#comment27 scenario #1. */
    @Test
    fun defaultConnections_carrierMergedViaMobileWithVcnTransport_mobileCarrierMergedWifiDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VcnTransportInfo(carrierMergedInfo))
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isTrue()
        }

    /** VPN over W+ (aka VPN over carrier merged). See b/352162710#comment27 scenario #2. */
    @Test
    @EnableFlags(FLAG_STATUS_BAR_ALWAYS_CHECK_UNDERLYING_NETWORKS)
    fun defaultConnections_vpnOverCarrierMerged_carrierMergedDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            // Underlying carrier merged network
            val underlyingCarrierMergedNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val underlyingCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingCarrierMergedNetwork))
                .thenReturn(underlyingCapabilities)

            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    // Transports are WIFI|VPN, *not* CELLULAR.
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_VPN)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(VpnTransportInfo(0, null, false, false))
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            assertThat(latest!!.carrierMerged.isDefault).isTrue()
        }

    @Test
    fun defaultConnections_notCarrierMergedViaWifi_carrierMergedNotDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(false) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.carrierMerged.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_notCarrierMergedViaMobile_carrierMergedNotDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(false) }
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.carrierMerged.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_transportInfoNotWifi_wifiNotDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.transportInfo).thenReturn(mock())
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(false)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.wifi.isDefault).isFalse()
        }

    @Test
    fun defaultConnections_nullUnderlyingInfo_noError() {
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(null)
            }

        getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)
        // No assert, just verify no error
    }

    @Test
    fun defaultConnections_underlyingInfoHasNullCapabilities_noError() {
        val underlyingNetworkWithNull = mock<Network>()
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetworkWithNull))
            .thenReturn(null)

        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetworkWithNull))
            }

        getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)
        // No assert, just verify no error
    }

    // This test verifies our internal API for completeness, but we don't expect this case to ever
    // happen in practice.
    @Test
    fun defaultConnections_cellular_underlyingCarrierMergedViaWifi_allDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            // Underlying carrier merged network
            val underlyingCarrierMergedNetwork = mock<Network>()
            val carrierMergedInfo =
                mock<WifiInfo>().apply { whenever(this.isCarrierMerged).thenReturn(true) }
            val underlyingCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(carrierMergedInfo)
                }
            whenever(connectivityManager.getNetworkCapabilities(underlyingCarrierMergedNetwork))
                .thenReturn(underlyingCapabilities)

            // Main network with underlying network
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isTrue()
        }

    /**
     * Test for b/225902574: VPN over VCN over W+ (aka VPN over VCN over carrier merged).
     *
     * Also see b/352162710#comment27 scenario #3 and b/352162710#comment30.
     */
    @Test
    fun defaultConnections_cellular_underlyingCarrierMergedViaMobileWithVcnTransport_allDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            // Underlying carrier merged network
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

            // Main network with underlying network
            val mainCapabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_VPN)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                    whenever(it.underlyingNetworks)
                        .thenReturn(listOf(underlyingCarrierMergedNetwork))
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, mainCapabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.carrierMerged.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isTrue()
        }

    @Test
    fun defaultConnections_multipleTransports_multipleDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(true)
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.mobile.isDefault).isTrue()
            assertThat(latest!!.ethernet.isDefault).isTrue()
            assertThat(latest!!.wifi.isDefault).isTrue()
        }

    @Test
    fun defaultConnections_hasValidated_isValidatedTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .thenReturn(true)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.isValidated).isTrue()
        }

    @Test
    fun defaultConnections_noValidated_isValidatedFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.defaultConnections)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                        .thenReturn(false)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest!!.isValidated).isFalse()
        }

    @Test
    fun vcnSubId_initiallyNull() {
        assertThat(underTest.vcnSubId.value).isNull()
    }

    @Test
    fun vcnSubId_tracksVcnTransportInfo() =
        testScope.runTest {
            val vcnInfo = VcnTransportInfo(SUB_1_ID)

            val latest by collectLastValue(underTest.vcnSubId)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(vcnInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isEqualTo(SUB_1_ID)
        }

    @Test
    fun vcnSubId_filersOutInvalid() =
        testScope.runTest {
            val vcnInfo = VcnTransportInfo(INVALID_SUBSCRIPTION_ID)

            val latest by collectLastValue(underTest.vcnSubId)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(vcnInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isNull()
        }

    @Test
    fun vcnSubId_nullIfNoTransportInfo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.vcnSubId)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(null)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isNull()
        }

    @Test
    fun vcnSubId_nullIfVcnInfoIsNotCellular() =
        testScope.runTest {
            // If the underlying network of the VCN is a WiFi network, then there is no subId that
            // could disagree with telephony's active data subscription id.

            val latest by collectLastValue(underTest.vcnSubId)

            val wifiInfo = mock<WifiInfo>()
            val vcnInfo = VcnTransportInfo(wifiInfo)
            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(vcnInfo)
                }

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isNull()
        }

    @Test
    fun vcnSubId_changingVcnInfoIsTracked() =
        testScope.runTest {
            val latest by collectLastValue(underTest.vcnSubId)

            val wifiInfo = mock<WifiInfo>()
            val wifiVcnInfo = VcnTransportInfo(wifiInfo)
            val sub1VcnInfo = VcnTransportInfo(SUB_1_ID)
            val sub2VcnInfo = VcnTransportInfo(SUB_2_ID)

            val capabilities =
                mock<NetworkCapabilities>().also {
                    whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                    whenever(it.transportInfo).thenReturn(wifiVcnInfo)
                }

            // WIFI VCN info
            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isNull()

            // Cellular VCN info with subId 1
            whenever(capabilities.hasTransport(eq(TRANSPORT_CELLULAR))).thenReturn(true)
            whenever(capabilities.transportInfo).thenReturn(sub1VcnInfo)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isEqualTo(SUB_1_ID)

            // Cellular VCN info with subId 2
            whenever(capabilities.transportInfo).thenReturn(sub2VcnInfo)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isEqualTo(SUB_2_ID)

            // No VCN anymore
            whenever(capabilities.transportInfo).thenReturn(null)

            getDefaultNetworkCallback().onCapabilitiesChanged(NETWORK, capabilities)

            assertThat(latest).isNull()
        }

    @Test
    fun getMainOrUnderlyingWifiInfo_wifi_hasInfo() {
        val wifiInfo = mock<WifiInfo>()
        val capabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(wifiInfo)
            }

        val result = capabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        assertThat(result).isEqualTo(wifiInfo)
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_vcnWithWifi_hasInfo() {
        val wifiInfo = mock<WifiInfo>()
        val vcnInfo = VcnTransportInfo(wifiInfo)
        val capabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(vcnInfo)
            }

        val result = capabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        assertThat(result).isEqualTo(wifiInfo)
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_notCellularOrWifiTransport_noInfo() {
        val capabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                whenever(it.transportInfo).thenReturn(mock<WifiInfo>())
            }

        val result = capabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        assertThat(result).isNull()
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_cellular_underlyingWifi_hasInfo() {
        val underlyingNetwork = mock<Network>()
        val underlyingWifiInfo = mock<WifiInfo>()
        val underlyingWifiCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingWifiInfo)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
            .thenReturn(underlyingWifiCapabilities)

        // WHEN the main capabilities have an underlying wifi network
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN we fetch the underlying wifi info
        assertThat(result).isEqualTo(underlyingWifiInfo)
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_ALWAYS_CHECK_UNDERLYING_NETWORKS)
    fun getMainOrUnderlyingWifiInfo_notCellular_underlyingWifi_noInfo() {
        val underlyingNetwork = mock<Network>()
        val underlyingWifiInfo = mock<WifiInfo>()
        val underlyingWifiCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingWifiInfo)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
            .thenReturn(underlyingWifiCapabilities)

        // WHEN the main capabilities have an underlying wifi network but is *not* CELLULAR
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(true)
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN we DON'T fetch the underlying wifi info
        assertThat(result).isNull()
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_cellular_underlyingVcnWithWifi_hasInfo() {
        val wifiInfo = mock<WifiInfo>()
        val underlyingNetwork = mock<Network>()
        val underlyingVcnInfo = VcnTransportInfo(wifiInfo)
        val underlyingWifiCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingVcnInfo)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
            .thenReturn(underlyingWifiCapabilities)

        // WHEN the main capabilities have an underlying VCN network with wifi
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN we fetch the wifi info
        assertThat(result).isEqualTo(wifiInfo)
    }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_ALWAYS_CHECK_UNDERLYING_NETWORKS)
    fun getMainOrUnderlyingWifiInfo_notCellular_underlyingVcnWithWifi_noInfo() {
        val underlyingNetwork = mock<Network>()
        val underlyingVcnInfo = VcnTransportInfo(mock<WifiInfo>())
        val underlyingWifiCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingVcnInfo)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
            .thenReturn(underlyingWifiCapabilities)

        // WHEN the main capabilities have an underlying wifi network but it is *not* CELLULAR
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_ETHERNET)).thenReturn(true)
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(false)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN we DON'T fetch the underlying wifi info
        assertThat(result).isNull()
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_cellular_underlyingCellularWithCarrierMerged_hasInfo() {
        // Underlying carrier merged network
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

        // Main network with underlying network
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingCarrierMergedNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        assertThat(result).isEqualTo(carrierMergedInfo)
        assertThat(result!!.isCarrierMerged).isTrue()
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_multipleUnderlying_usesFirstNonNull() {
        // First underlying: Not wifi
        val underlyingNotWifiNetwork = mock<Network>()
        val underlyingNotWifiCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(false)
                whenever(it.transportInfo).thenReturn(null)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNotWifiNetwork))
            .thenReturn(underlyingNotWifiCapabilities)

        // Second underlying: wifi
        val underlyingWifiNetwork1 = mock<Network>()
        val underlyingWifiInfo1 = mock<WifiInfo>()
        val underlyingWifiCapabilities1 =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingWifiInfo1)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingWifiNetwork1))
            .thenReturn(underlyingWifiCapabilities1)

        // Third underlying: also wifi
        val underlyingWifiNetwork2 = mock<Network>()
        val underlyingWifiInfo2 = mock<WifiInfo>()
        val underlyingWifiCapabilities2 =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(underlyingWifiInfo2)
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingWifiNetwork2))
            .thenReturn(underlyingWifiCapabilities2)

        // WHEN the main capabilities has multiple underlying networks
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks)
                    .thenReturn(
                        listOf(
                            underlyingNotWifiNetwork,
                            underlyingWifiNetwork1,
                            underlyingWifiNetwork2,
                        )
                    )
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN the first wifi one is used
        assertThat(result).isEqualTo(underlyingWifiInfo1)
    }

    @Test
    fun getMainOrUnderlyingWifiInfo_nestedUnderlying_doesNotLookAtNested() {
        // WHEN there are two layers of underlying networks...

        // Nested network
        val nestedUnderlyingNetwork = mock<Network>()
        val nestedWifiInfo = mock<WifiInfo>()
        val nestedCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_WIFI)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(nestedWifiInfo)
            }
        whenever(connectivityManager.getNetworkCapabilities(nestedUnderlyingNetwork))
            .thenReturn(nestedCapabilities)

        // Underlying network containing the nested network
        val underlyingNetwork = mock<Network>()
        val underlyingCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(nestedUnderlyingNetwork))
            }
        whenever(connectivityManager.getNetworkCapabilities(underlyingNetwork))
            .thenReturn(underlyingCapabilities)

        // Main network containing the underlying network, which contains the nested network
        val mainCapabilities =
            mock<NetworkCapabilities>().also {
                whenever(it.hasTransport(TRANSPORT_CELLULAR)).thenReturn(true)
                whenever(it.transportInfo).thenReturn(null)
                whenever(it.underlyingNetworks).thenReturn(listOf(underlyingNetwork))
            }

        val result = mainCapabilities.getMainOrUnderlyingWifiInfo(connectivityManager)

        // THEN only the first layer is checked, and the first layer has no wifi info
        assertThat(result).isNull()
    }

    private fun createAndSetRepo() {
        underTest =
            ConnectivityRepositoryImpl(
                connectivityManager,
                connectivitySlots,
                context,
                dumpManager,
                logger,
                testScope.backgroundScope,
                tunerService,
            )
        testScope.runCurrent()
    }

    private fun getTunable(): TunerService.Tunable {
        val callbackCaptor = argumentCaptor<TunerService.Tunable>()
        verify(tunerService).addTunable(callbackCaptor.capture(), any())
        return callbackCaptor.firstValue
    }

    private fun setUpEthernetWifiMobileSlotNames() {
        whenever(connectivitySlots.getSlotFromName(SLOT_ETHERNET))
            .thenReturn(ConnectivitySlot.ETHERNET)
        whenever(connectivitySlots.getSlotFromName(SLOT_WIFI)).thenReturn(ConnectivitySlot.WIFI)
        whenever(connectivitySlots.getSlotFromName(SLOT_MOBILE)).thenReturn(ConnectivitySlot.MOBILE)
    }

    private fun getDefaultNetworkCallback(): ConnectivityManager.NetworkCallback {
        val callbackCaptor = argumentCaptor<ConnectivityManager.NetworkCallback>()
        verify(connectivityManager).registerDefaultNetworkCallback(callbackCaptor.capture())
        return callbackCaptor.firstValue
    }

    private companion object {
        private const val SLOT_ETHERNET = "ethernet"
        private const val SLOT_WIFI = "wifi"
        private const val SLOT_MOBILE = "mobile"

        private const val SUB_1_ID = 1
        private const val SUB_2_ID = 2

        const val NETWORK_ID = 45
        val NETWORK = mock<Network>().apply { whenever(this.getNetId()).thenReturn(NETWORK_ID) }
    }
}
