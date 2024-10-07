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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_OTHER_DEVICE_CONNECTION
import com.android.systemui.Flags.FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription.Companion.loadContentDescription
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.statusbar.connectivity.WifiIcons
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel.Companion.viewModelForLocation
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class WifiViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private lateinit var underTest: WifiViewModel

    private val tableLogBuffer = logcatTableLogBuffer(kosmos, "WifiViewModelTest")
    @Mock private lateinit var connectivityConstants: ConnectivityConstants
    @Mock private lateinit var wifiConstants: WifiConstants
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor
    private lateinit var airplaneModeViewModel: AirplaneModeViewModel
    private val shouldShowSignalSpacerProviderFlow = MutableStateFlow(false)
    private val testScope = TestScope()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        wifiRepository.setIsWifiEnabled(true)
        interactor =
            WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)
        airplaneModeViewModel =
            AirplaneModeViewModelImpl(
                AirplaneModeInteractor(
                    airplaneModeRepository,
                    connectivityRepository,
                    kosmos.fakeMobileConnectionsRepository,
                ),
                tableLogBuffer,
                testScope.backgroundScope,
            )

        createAndSetViewModel()
    }

    // See [WifiViewModelIconParameterizedTest] for additional view model tests.

    // Note on testing: [WifiViewModel] exposes 3 different instances of
    // [LocationBasedWifiViewModel]. In practice, these 3 different instances will get the exact
    // same data for icon, activity, etc. flows. So, most of these tests will test just one of the
    // instances. There are also some tests that verify all 3 instances received the same data.

    @Test
    fun wifiIcon_allLocationViewModelsReceiveSameData() =
        testScope.runTest {
            val home = viewModelForLocation(underTest, StatusBarLocation.HOME)
            val keyguard = viewModelForLocation(underTest, StatusBarLocation.KEYGUARD)
            val qs = viewModelForLocation(underTest, StatusBarLocation.QS)

            val latestHome by collectLastValue(home.wifiIcon)
            val latestKeyguard by collectLastValue(keyguard.wifiIcon)
            val latestQs by collectLastValue(qs.wifiIcon)

            wifiRepository.setWifiNetwork(WifiNetworkModel.Active.of(isValidated = true, level = 1))

            assertThat(latestHome).isInstanceOf(WifiIcon.Visible::class.java)
            assertThat(latestHome).isEqualTo(latestKeyguard)
            assertThat(latestKeyguard).isEqualTo(latestQs)
        }

    @Test
    fun wifiIcon_validHotspot_hotspotIconNotShown() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiIcon)

            // Even WHEN the network has a valid hotspot type
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(
                    isValidated = true,
                    level = 1,
                    hotspotDeviceType = WifiNetworkModel.HotspotDeviceType.LAPTOP,
                )
            )

            // THEN the hotspot icon is not used for the status bar icon, and the typical wifi icon
            // is used instead
            assertThat(latest).isInstanceOf(WifiIcon.Visible::class.java)
            assertThat((latest as WifiIcon.Visible).res).isEqualTo(WifiIcons.WIFI_FULL_ICONS[1])
            assertThat(
                    (latest as WifiIcon.Visible).contentDescription.loadContentDescription(context)
                )
                .doesNotContain(context.getString(WIFI_OTHER_DEVICE_CONNECTION))
        }

    @Test
    fun activity_showActivityConfigFalse_outputsFalse() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(false)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val activityIn by collectLastValue(underTest.isActivityInViewVisible)
            val activityOut by collectLastValue(underTest.isActivityOutViewVisible)
            val activityContainer by collectLastValue(underTest.isActivityContainerVisible)

            // Verify that on launch, we receive false.
            assertThat(activityIn).isFalse()
            assertThat(activityOut).isFalse()
            assertThat(activityContainer).isFalse()
        }

    @Test
    fun activity_showActivityConfigFalse_noUpdatesReceived() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(false)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val activityIn by collectLastValue(underTest.isActivityInViewVisible)
            val activityOut by collectLastValue(underTest.isActivityOutViewVisible)
            val activityContainer by collectLastValue(underTest.isActivityContainerVisible)

            // WHEN we update the repo to have activity
            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            // THEN we didn't update to the new activity (because our config is false)
            assertThat(activityIn).isFalse()
            assertThat(activityOut).isFalse()
            assertThat(activityContainer).isFalse()
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun activity_nullSsid_outputsFalse_staticFlagOff() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()

            wifiRepository.setWifiNetwork(WifiNetworkModel.Active.of(ssid = null, level = 1))

            val activityIn by collectLastValue(underTest.isActivityInViewVisible)
            val activityOut by collectLastValue(underTest.isActivityOutViewVisible)
            val activityContainer by collectLastValue(underTest.isActivityContainerVisible)

            // WHEN we update the repo to have activity
            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            // THEN we still output false because our network's SSID is null
            assertThat(activityIn).isFalse()
            assertThat(activityOut).isFalse()
            assertThat(activityContainer).isFalse()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun activity_nullSsid_outputsFalse_staticFlagOn() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()

            wifiRepository.setWifiNetwork(WifiNetworkModel.Active.of(ssid = null, level = 1))

            val activityIn by collectLastValue(underTest.isActivityInViewVisible)
            val activityOut by collectLastValue(underTest.isActivityOutViewVisible)
            val activityContainer by collectLastValue(underTest.isActivityContainerVisible)

            // WHEN we update the repo to have activity
            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            // THEN we still output false because our network's SSID is null
            assertThat(activityIn).isFalse()
            assertThat(activityOut).isFalse()

            // THEN the inout indicators are sill showing due to the config being true
            assertThat(activityContainer).isTrue()
        }

    @Test
    fun activity_allLocationViewModelsReceiveSameData() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val home = viewModelForLocation(underTest, StatusBarLocation.HOME)
            val keyguard = viewModelForLocation(underTest, StatusBarLocation.KEYGUARD)
            val qs = viewModelForLocation(underTest, StatusBarLocation.QS)

            val latestHome by collectLastValue(home.isActivityInViewVisible)
            val latestKeyguard by collectLastValue(keyguard.isActivityInViewVisible)
            val latestQs by collectLastValue(qs.isActivityInViewVisible)

            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            assertThat(latestHome).isTrue()
            assertThat(latestKeyguard).isTrue()
            assertThat(latestQs).isTrue()
        }

    @Test
    fun activityIn_hasActivityInTrue_outputsTrue() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityInViewVisible)

            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isTrue()
        }

    @Test
    fun activityIn_hasActivityInFalse_outputsFalse() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityInViewVisible)

            val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isFalse()
        }

    @Test
    fun activityOut_hasActivityOutTrue_outputsTrue() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityOutViewVisible)

            val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isTrue()
        }

    @Test
    fun activityOut_hasActivityOutFalse_outputsFalse() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityOutViewVisible)

            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isFalse()
        }

    @Test
    fun activityContainer_hasActivityInTrue_outputsTrue() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityContainerVisible)

            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isTrue()
        }

    @Test
    fun activityContainer_hasActivityOutTrue_outputsTrue() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityContainerVisible)

            val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isTrue()
        }

    @Test
    fun activityContainer_inAndOutTrue_outputsTrue() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityContainerVisible)

            val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isTrue()
        }

    @Test
    @DisableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun activityContainer_inAndOutFalse_outputsTrue_staticFlagOff() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityContainerVisible)

            val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity)

            assertThat(latest).isFalse()
        }

    @Test
    @EnableFlags(FLAG_STATUS_BAR_STATIC_INOUT_INDICATORS)
    fun activityContainer_inAndOutFalse_outputsTrue_staticFlagOn() =
        testScope.runTest {
            whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
            createAndSetViewModel()
            wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

            val latest by collectLastValue(underTest.isActivityContainerVisible)

            val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity)

            // The activity container should always be visible, since activity is
            // shown in UI by changing opacity of the indicators.
            assertThat(latest).isTrue()
        }

    @Test
    fun airplaneSpacer_notAirplaneMode_outputsFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAirplaneSpacerVisible)

            airplaneModeRepository.setIsAirplaneMode(false)

            assertThat(latest).isFalse()
        }

    @Test
    fun airplaneSpacer_airplaneForceHidden_outputsFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAirplaneSpacerVisible)

            airplaneModeRepository.setIsAirplaneMode(true)
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.AIRPLANE))

            assertThat(latest).isFalse()
        }

    @Test
    fun airplaneSpacer_airplaneIconVisible_outputsTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAirplaneSpacerVisible)

            airplaneModeRepository.setIsAirplaneMode(true)

            assertThat(latest).isTrue()
        }

    @Test
    fun signalSpacer_firstSubNotShowingNetworkTypeIcon_outputsFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSignalSpacerVisible)

            shouldShowSignalSpacerProviderFlow.value = false

            assertThat(latest).isFalse()
        }

    @Test
    fun signalSpacer_firstSubIsShowingNetworkTypeIcon_outputsTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isSignalSpacerVisible)

            shouldShowSignalSpacerProviderFlow.value = true

            assertThat(latest).isTrue()
        }

    private fun createAndSetViewModel() {
        // [WifiViewModel] creates its flows as soon as it's instantiated, and some of those flow
        // creations rely on certain config values that we mock out in individual tests. This method
        // allows tests to create the view model only after those configs are correctly set up.
        underTest =
            WifiViewModel(
                airplaneModeViewModel,
                { shouldShowSignalSpacerProviderFlow },
                connectivityConstants,
                context,
                tableLogBuffer,
                interactor,
                testScope.backgroundScope,
                wifiConstants,
            )
    }

    companion object {
        private val ACTIVE_VALID_WIFI_NETWORK = WifiNetworkModel.Active.of(ssid = "AB", level = 1)
    }
}
