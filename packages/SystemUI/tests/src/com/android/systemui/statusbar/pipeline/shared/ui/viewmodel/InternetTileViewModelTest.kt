/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.shared.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Text
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.qs.tileimpl.QSTileImpl.ResourceIcon
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeUserSetupRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.ui.model.SignalIcon
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.InternetTileViewModel.Companion.NOT_CONNECTED_NETWORKS_UNAVAILABLE
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@SmallTest
class InternetTileViewModelTest : SysuiTestCase() {
    private lateinit var underTest: InternetTileViewModel
    private lateinit var mobileIconsInteractor: MobileIconsInteractor

    private val airplaneModeRepository = FakeAirplaneModeRepository()
    private val connectivityRepository = FakeConnectivityRepository()
    private val ethernetInteractor = EthernetInteractor(connectivityRepository)
    private val wifiRepository = FakeWifiRepository()
    private val userSetupRepo = FakeUserSetupRepository()
    private val testScope = TestScope()
    private val wifiInteractor =
        WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)

    private val tableLogBuffer: TableLogBuffer = mock()
    private val carrierConfigTracker: CarrierConfigTracker = mock()

    private val mobileConnectionsRepository =
        FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), tableLogBuffer)
    private val mobileConnectionRepository =
        FakeMobileConnectionRepository(SUB_1_ID, tableLogBuffer)

    @Before
    fun setUp() {
        mobileConnectionRepository.apply {
            setNetworkTypeKey(mobileConnectionsRepository.GSM_KEY)
            isInService.value = true
            dataConnectionState.value = DataConnectionState.Connected
            dataEnabled.value = true
        }

        mobileConnectionsRepository.apply {
            activeMobileDataRepository.value = mobileConnectionRepository
            activeMobileDataSubscriptionId.value = SUB_1_ID
            setMobileConnectionRepositoryMap(mapOf(SUB_1_ID to mobileConnectionRepository))
        }

        mobileIconsInteractor =
            MobileIconsInteractorImpl(
                mobileConnectionsRepository,
                carrierConfigTracker,
                tableLogBuffer,
                connectivityRepository,
                userSetupRepo,
                testScope.backgroundScope,
                context,
            )

        underTest =
            InternetTileViewModel(
                airplaneModeRepository,
                connectivityRepository,
                ethernetInteractor,
                mobileIconsInteractor,
                wifiInteractor,
                context,
                testScope.backgroundScope,
            )
    }

    @Test
    fun noDefault_noNetworksAvailable() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            connectivityRepository.defaultConnections.value = DefaultConnectionModel()

            assertThat(latest?.secondaryLabel)
                .isEqualTo(Text.Resource(R.string.quick_settings_networks_unavailable))
            assertThat(latest?.iconId).isEqualTo(R.drawable.ic_qs_no_internet_unavailable)
        }

    @Test
    fun noDefault_networksAvailable() =
        testScope.runTest {
            // TODO: support [WifiInteractor.areNetworksAvailable]
        }

    @Test
    fun wifiDefaultAndActive() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            val networkModel =
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 4,
                    ssid = "test ssid",
                )

            connectivityRepository.setWifiConnected()
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)

            assertThat(latest?.secondaryTitle).isEqualTo("test ssid")
            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(WifiIcons.WIFI_NO_INTERNET_ICONS[4]))
            assertThat(latest?.iconId).isNull()
        }

    @Test
    fun wifiDefaultAndActive_hotspotNone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            val networkModel =
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 4,
                    ssid = "test ssid",
                    hotspotDeviceType = WifiNetworkModel.HotspotDeviceType.NONE,
                )

            connectivityRepository.setWifiConnected()
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(WifiIcons.WIFI_NO_INTERNET_ICONS[4]))
        }

    @Test
    fun wifiDefaultAndActive_hotspotTablet() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.TABLET)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_tablet))
        }

    @Test
    fun wifiDefaultAndActive_hotspotLaptop() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.LAPTOP)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_laptop))
        }

    @Test
    fun wifiDefaultAndActive_hotspotWatch() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.WATCH)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_watch))
        }

    @Test
    fun wifiDefaultAndActive_hotspotAuto() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.AUTO)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_auto))
        }

    @Test
    fun wifiDefaultAndActive_hotspotPhone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.PHONE)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_phone))
        }

    @Test
    fun wifiDefaultAndActive_hotspotUnknown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.UNKNOWN)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_phone))
        }

    @Test
    fun wifiDefaultAndActive_hotspotInvalid() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.INVALID)

            assertThat(latest?.icon)
                .isEqualTo(ResourceIcon.get(com.android.settingslib.R.drawable.ic_hotspot_phone))
        }

    @Test
    fun wifiDefaultAndNotActive_noNetworksAvailable() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            val networkModel = WifiNetworkModel.Inactive

            connectivityRepository.setWifiConnected(validated = false)
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)
            wifiRepository.wifiScanResults.value = emptyList()

            assertThat(latest).isEqualTo(NOT_CONNECTED_NETWORKS_UNAVAILABLE)
        }

    @Test
    fun wifiDefaultAndNotActive_networksAvailable() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            val networkModel = WifiNetworkModel.Inactive

            connectivityRepository.setWifiConnected(validated = false)
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)
            wifiRepository.wifiScanResults.value = listOf(WifiScanEntry("test 1"))

            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.secondaryTitle)
                .isEqualTo(context.getString(R.string.quick_settings_networks_available))
            assertThat(latest?.icon).isNull()
            assertThat(latest?.iconId).isEqualTo(R.drawable.ic_qs_no_internet_available)
        }

    @Test
    fun mobileDefault_usesNetworkNameAndIcon() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)

            connectivityRepository.setMobileConnected()
            mobileConnectionsRepository.mobileIsDefault.value = true
            mobileConnectionRepository.apply {
                setAllLevels(3)
                setAllRoaming(false)
                networkName.value = NetworkNameModel.Default("test network")
            }

            assertThat(latest?.secondaryTitle).contains("test network")
            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.icon).isInstanceOf(SignalIcon::class.java)
            assertThat(latest?.iconId).isNull()
        }

    @Test
    fun ethernetDefault_validated_matchesInteractor() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)
            val ethernetIcon by collectLastValue(ethernetInteractor.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = true)

            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.secondaryTitle)
                .isEqualTo(ethernetIcon!!.contentDescription.toString())
            assertThat(latest?.iconId).isEqualTo(R.drawable.stat_sys_ethernet_fully)
            assertThat(latest?.icon).isNull()
        }

    @Test
    fun ethernetDefault_notValidated_matchesInteractor() =
        testScope.runTest {
            val latest by collectLastValue(underTest.tileModel)
            val ethernetIcon by collectLastValue(ethernetInteractor.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = false)

            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.secondaryTitle)
                .isEqualTo(ethernetIcon!!.contentDescription.toString())
            assertThat(latest?.iconId).isEqualTo(R.drawable.stat_sys_ethernet)
            assertThat(latest?.icon).isNull()
        }

    private fun setWifiNetworkWithHotspot(hotspot: WifiNetworkModel.HotspotDeviceType) {
        val networkModel =
            WifiNetworkModel.Active(
                networkId = 1,
                level = 4,
                ssid = "test ssid",
                hotspotDeviceType = hotspot,
            )

        connectivityRepository.setWifiConnected()
        wifiRepository.setIsWifiDefault(true)
        wifiRepository.setWifiNetwork(networkModel)
    }

    companion object {
        const val SUB_1_ID = 1
    }
}
