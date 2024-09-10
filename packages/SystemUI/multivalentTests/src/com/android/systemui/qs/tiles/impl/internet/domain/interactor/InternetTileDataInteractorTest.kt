/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.internet.domain.interactor

import android.graphics.drawable.TestStubDrawable
import android.os.UserHandle
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.AccessibilityContentDescriptions
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.common.shared.model.Text
import com.android.systemui.common.shared.model.Text.Companion.loadText
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.internet.domain.model.InternetTileModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.MobileIconsInteractorImpl
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.policy.data.repository.FakeUserSetupRepository
import com.android.systemui.util.CarrierConfigTracker
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class InternetTileDataInteractorTest : SysuiTestCase() {
    private val testUser = UserHandle.of(1)
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: InternetTileDataInteractor
    private lateinit var mobileIconsInteractor: MobileIconsInteractor

    private val airplaneModeRepository = FakeAirplaneModeRepository()
    private val connectivityRepository = FakeConnectivityRepository()
    private val ethernetInteractor = EthernetInteractor(connectivityRepository)
    private val wifiRepository = FakeWifiRepository()
    private val userSetupRepo = FakeUserSetupRepository()
    private val wifiInteractor =
        WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)

    private val tableLogBuffer = logcatTableLogBuffer(kosmos, "InternetTileDataInteractorTest")
    private val carrierConfigTracker: CarrierConfigTracker = mock()

    private val mobileConnectionsRepository =
        FakeMobileConnectionsRepository(FakeMobileMappingsProxy(), tableLogBuffer)
    private val mobileConnectionRepository =
        FakeMobileConnectionRepository(SUB_1_ID, tableLogBuffer)

    private val flags =
        FakeFeatureFlagsClassic().also {
            it.set(Flags.FILTER_PROVISIONING_NETWORK_SUBSCRIPTIONS, true)
        }

    private val internet = context.getString(R.string.quick_settings_internet_label)

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
                flags,
            )

        context.orCreateTestableResources.apply {
            addOverride(com.android.internal.R.drawable.ic_signal_cellular, TestStubDrawable())
            addOverride(com.android.settingslib.R.drawable.ic_hotspot_phone, TestStubDrawable())
            addOverride(com.android.settingslib.R.drawable.ic_hotspot_laptop, TestStubDrawable())
            addOverride(com.android.settingslib.R.drawable.ic_hotspot_tablet, TestStubDrawable())
            addOverride(com.android.settingslib.R.drawable.ic_hotspot_watch, TestStubDrawable())
            addOverride(com.android.settingslib.R.drawable.ic_hotspot_auto, TestStubDrawable())

            WifiIcons.WIFI_NO_INTERNET_ICONS.forEach { iconId ->
                addOverride(iconId, TestStubDrawable())
            }
        }

        underTest =
            InternetTileDataInteractor(
                context,
                testScope.coroutineContext,
                testScope.backgroundScope,
                airplaneModeRepository,
                connectivityRepository,
                ethernetInteractor,
                mobileIconsInteractor,
                wifiInteractor,
            )
    }

    @Test
    fun noDefault_noNetworksAvailable() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            connectivityRepository.defaultConnections.value = DefaultConnectionModel()

            assertThat(latest?.secondaryLabel)
                .isEqualTo(Text.Resource(R.string.quick_settings_networks_unavailable))
            assertThat(latest?.iconId).isEqualTo(R.drawable.ic_qs_no_internet_unavailable)
        }

    @Test
    fun noDefault_networksAvailable() =
        testScope.runTest {
            // TODO(b/328419203): support [WifiInteractor.areNetworksAvailable]
        }

    @Test
    fun wifiDefaultAndActive() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            val networkModel =
                WifiNetworkModel.Active.of(
                    level = 4,
                    ssid = "test ssid",
                )
            val wifiIcon =
                WifiIcon.fromModel(model = networkModel, context = context, showHotspotInfo = true)
                    as WifiIcon.Visible

            connectivityRepository.setWifiConnected()
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)

            assertThat(latest?.secondaryTitle).isEqualTo("test ssid")
            assertThat(latest?.secondaryLabel).isNull()
            val expectedIcon =
                Icon.Loaded(context.getDrawable(WifiIcons.WIFI_NO_INTERNET_ICONS[4])!!, null)

            val actualIcon = latest?.icon
            assertThat(actualIcon).isEqualTo(expectedIcon)
            assertThat(latest?.iconId).isEqualTo(WifiIcons.WIFI_NO_INTERNET_ICONS[4])
            assertThat(latest?.contentDescription.loadContentDescription(context))
                .isEqualTo("$internet,test ssid")
            val expectedSd = wifiIcon.contentDescription
            assertThat(latest?.stateDescription).isEqualTo(expectedSd)
        }

    @Test
    fun wifiDefaultAndActive_hotspotNone() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            val networkModel =
                WifiNetworkModel.Active.of(
                    level = 4,
                    ssid = "test ssid",
                    hotspotDeviceType = WifiNetworkModel.HotspotDeviceType.NONE,
                )

            connectivityRepository.setWifiConnected()
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)

            val expectedIcon =
                Icon.Loaded(context.getDrawable(WifiIcons.WIFI_NO_INTERNET_ICONS[4])!!, null)
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .doesNotContain(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotTablet() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.TABLET)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_tablet)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotLaptop() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.LAPTOP)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_laptop)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotWatch() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.WATCH)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_watch)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotAuto() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.AUTO)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_auto)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotPhone() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.PHONE)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_phone)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotUnknown() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.UNKNOWN)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_phone)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndActive_hotspotInvalid() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            setWifiNetworkWithHotspot(WifiNetworkModel.HotspotDeviceType.INVALID)

            val expectedIcon =
                Icon.Loaded(
                    context.getDrawable(com.android.settingslib.R.drawable.ic_hotspot_phone)!!,
                    null
                )
            assertThat(latest?.icon).isEqualTo(expectedIcon)
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(
                    context.getString(AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION)
                )
        }

    @Test
    fun wifiDefaultAndNotActive_noNetworksAvailable() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            val networkModel = WifiNetworkModel.Inactive()

            connectivityRepository.setWifiConnected(validated = false)
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)
            wifiRepository.wifiScanResults.value = emptyList()

            assertThat(latest).isEqualTo(NOT_CONNECTED_NETWORKS_UNAVAILABLE)
        }

    @Test
    fun wifiDefaultAndNotActive_networksAvailable() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            val networkModel = WifiNetworkModel.Inactive()

            connectivityRepository.setWifiConnected(validated = false)
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(networkModel)
            wifiRepository.wifiScanResults.value = listOf(WifiScanEntry("test 1"))

            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.secondaryTitle)
                .isEqualTo(context.getString(R.string.quick_settings_networks_available))
            assertThat(latest?.icon).isNull()
            assertThat(latest?.iconId).isEqualTo(R.drawable.ic_qs_no_internet_available)
            assertThat(latest?.stateDescription).isNull()
            val expectedCd =
                "$internet,${context.getString(R.string.quick_settings_networks_available)}"
            assertThat(latest?.contentDescription.loadContentDescription(context))
                .isEqualTo(expectedCd)
        }

    /**
     * We expect a RuntimeException because [underTest] instantiates a SignalDrawable on the
     * provided context, and so the SignalDrawable constructor attempts to instantiate a Handler()
     * on the mentioned context. Since that context does not have a looper assigned to it, the
     * handler instantiation will throw a RuntimeException.
     *
     * TODO(b/338068066): Robolectric behavior differs in that it does not throw the exception So
     *   either we should make Robolectric behave similar to the device test, or change this test to
     *   look for a different signal than the exception, when run by Robolectric. For now we just
     *   assume the test is not Robolectric.
     */
    @Test(expected = java.lang.RuntimeException::class)
    fun mobileDefault_usesNetworkNameAndIcon_throwsRunTimeException() =
        testScope.runTest {
            assumeFalse(isRobolectricTest())

            collectLastValue(underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest)))

            connectivityRepository.setMobileConnected()
            mobileConnectionsRepository.mobileIsDefault.value = true
            mobileConnectionRepository.apply {
                setAllLevels(3)
                setAllRoaming(false)
                networkName.value = NetworkNameModel.Default("test network")
            }

            runCurrent()
        }

    /**
     * See [mobileDefault_usesNetworkNameAndIcon_throwsRunTimeException] for description of the
     * problem this test solves. The solution here is to assign a looper to the context via
     * RunWithLooper. In the production code, the solution is to use a Main CoroutineContext for
     * creating the SignalDrawable.
     */
    @TestableLooper.RunWithLooper
    @Test
    fun mobileDefault_run_withLooper_usesNetworkNameAndIcon() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )

            connectivityRepository.setMobileConnected()
            mobileConnectionsRepository.mobileIsDefault.value = true
            mobileConnectionRepository.apply {
                setAllLevels(3)
                setAllRoaming(false)
                networkName.value = NetworkNameModel.Default("test network")
            }

            assertThat(latest).isNotNull()
            assertThat(latest?.secondaryTitle).isNotNull()
            assertThat(latest?.secondaryTitle.toString()).contains("test network")
            assertThat(latest?.secondaryLabel).isNull()
            assertThat(latest?.icon).isInstanceOf(Icon.Loaded::class.java)
            assertThat(latest?.iconId).isNull()
            assertThat(latest?.stateDescription.loadContentDescription(context))
                .isEqualTo(latest?.secondaryTitle.toString())
            assertThat(latest?.contentDescription.loadContentDescription(context))
                .isEqualTo(internet)
        }

    @Test
    fun ethernetDefault_validated_matchesInteractor() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            val ethernetIcon by collectLastValue(ethernetInteractor.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = true)

            assertThat(latest?.secondaryLabel.loadText(context))
                .isEqualTo(ethernetIcon!!.contentDescription.loadContentDescription(context))
            assertThat(latest?.secondaryTitle).isNull()
            assertThat(latest?.iconId).isEqualTo(R.drawable.stat_sys_ethernet_fully)
            assertThat(latest?.icon).isNull()
            assertThat(latest?.stateDescription).isNull()
            assertThat(latest?.contentDescription.loadContentDescription(context))
                .isEqualTo(latest?.secondaryLabel.loadText(context))
        }

    @Test
    fun ethernetDefault_notValidated_matchesInteractor() =
        testScope.runTest {
            val latest by
                collectLastValue(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            val ethernetIcon by collectLastValue(ethernetInteractor.icon)

            connectivityRepository.setEthernetConnected(default = true, validated = false)

            assertThat(latest?.secondaryLabel.loadText(context))
                .isEqualTo(ethernetIcon!!.contentDescription.loadContentDescription(context))
            assertThat(latest?.secondaryTitle).isNull()
            assertThat(latest?.iconId).isEqualTo(R.drawable.stat_sys_ethernet)
            assertThat(latest?.icon).isNull()
            assertThat(latest?.stateDescription).isNull()
            assertThat(latest?.contentDescription.loadContentDescription(context))
                .isEqualTo(latest?.secondaryLabel.loadText(context))
        }

    private fun setWifiNetworkWithHotspot(hotspot: WifiNetworkModel.HotspotDeviceType) {
        val networkModel =
            WifiNetworkModel.Active.of(
                level = 4,
                ssid = "test ssid",
                hotspotDeviceType = hotspot,
            )

        connectivityRepository.setWifiConnected()
        wifiRepository.setIsWifiDefault(true)
        wifiRepository.setWifiNetwork(networkModel)
    }

    private companion object {
        const val SUB_1_ID = 1

        val NOT_CONNECTED_NETWORKS_UNAVAILABLE =
            InternetTileModel.Inactive(
                secondaryLabel = Text.Resource(R.string.quick_settings_networks_unavailable),
                iconId = R.drawable.ic_qs_no_internet_unavailable,
                stateDescription = null,
                contentDescription =
                    ContentDescription.Resource(R.string.quick_settings_networks_unavailable),
            )
    }
}
