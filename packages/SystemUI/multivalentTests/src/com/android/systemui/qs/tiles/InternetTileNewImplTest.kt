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

package com.android.systemui.qs.tiles

import android.os.Handler
import android.service.quicksettings.Tile
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tiles.dialog.InternetDialogManager
import com.android.systemui.qs.tiles.dialog.WifiStateWorker
import com.android.systemui.res.R
import com.android.systemui.statusbar.connectivity.AccessPointController
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.ethernet.domain.EthernetInteractor
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel
import com.android.systemui.statusbar.pipeline.shared.data.model.DefaultConnectionModel.Wifi
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.shared.ui.viewmodel.InternetTileViewModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class InternetTileNewImplTest : SysuiTestCase() {
    lateinit var underTest: InternetTileNewImpl

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private var airplaneModeRepository = FakeAirplaneModeRepository()
    private var connectivityRepository = FakeConnectivityRepository()
    private var ethernetInteractor = EthernetInteractor(connectivityRepository)
    private var mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private var wifiRepository = FakeWifiRepository()
    private var wifiInteractor =
        WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)
    private lateinit var viewModel: InternetTileViewModel

    private lateinit var looper: TestableLooper

    @Mock private lateinit var host: QSHost
    @Mock private lateinit var eventLogger: QsEventLogger
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var sbStateController: StatusBarStateController
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var logger: QSLogger
    @Mock private lateinit var dialogManager: InternetDialogManager
    @Mock private lateinit var wifiStateWorker: WifiStateWorker
    @Mock private lateinit var accessPointController: AccessPointController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        looper = TestableLooper.get(this)

        // Allow the tile to load resources
        whenever(host.context).thenReturn(context)
        whenever(host.userContext).thenReturn(context)

        viewModel =
            InternetTileViewModel(
                airplaneModeRepository,
                connectivityRepository,
                ethernetInteractor,
                mobileIconsInteractor,
                wifiInteractor,
                context,
                testScope.backgroundScope,
            )

        underTest =
            InternetTileNewImpl(
                host,
                eventLogger,
                looper.looper,
                Handler(looper.looper),
                FalsingManagerFake(),
                metricsLogger,
                sbStateController,
                activityStarter,
                logger,
                viewModel,
                dialogManager,
                wifiStateWorker,
                accessPointController
            )

        underTest.initialize()
        underTest.setListening(Object(), true)

        looper.processAllMessages()
    }

    @Test
    fun noDefaultConnection_noNetworkAvailable() =
        testScope.runTest {
            connectivityRepository.defaultConnections.value = DefaultConnectionModel()
            wifiRepository.wifiScanResults.value = listOf()

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.secondaryLabel.toString())
                .isEqualTo(context.getString(R.string.quick_settings_networks_unavailable))
            assertThat(underTest.state.state).isEqualTo(Tile.STATE_INACTIVE)
        }

    @Test
    fun noDefaultConnection_networksAvailable() =
        testScope.runTest {
            connectivityRepository.defaultConnections.value = DefaultConnectionModel()
            wifiRepository.wifiScanResults.value =
                listOf(
                    WifiScanEntry(ssid = "ssid 1"),
                    WifiScanEntry(ssid = "ssid 2"),
                )

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.secondaryLabel.toString())
                .isEqualTo(context.getString(R.string.quick_settings_networks_available))
            assertThat(underTest.state.state).isEqualTo(1)
        }

    @Test
    fun airplaneMode_enabled_wifiDisabled() =
        testScope.runTest {
            airplaneModeRepository.setIsAirplaneMode(true)
            connectivityRepository.defaultConnections.value = DefaultConnectionModel()
            wifiRepository.setIsWifiEnabled(false)

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.state).isEqualTo(Tile.STATE_INACTIVE)
            assertThat(underTest.state.secondaryLabel)
                .isEqualTo(context.getString(R.string.status_bar_airplane))
        }

    @Test
    fun airplaneMode_enabled_wifiEnabledButNotConnected() =
        testScope.runTest {
            airplaneModeRepository.setIsAirplaneMode(true)
            connectivityRepository.defaultConnections.value = DefaultConnectionModel()
            wifiRepository.setIsWifiEnabled(true)

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.state).isEqualTo(Tile.STATE_INACTIVE)
            assertThat(underTest.state.secondaryLabel)
                .isEqualTo(context.getString(R.string.status_bar_airplane))
        }

    @Test
    fun airplaneMode_enabled_wifiEnabledAndConnected() =
        testScope.runTest {
            airplaneModeRepository.setIsAirplaneMode(true)
            connectivityRepository.defaultConnections.value =
                DefaultConnectionModel(
                    wifi = Wifi(true),
                    isValidated = true,
                )
            wifiRepository.setIsWifiEnabled(true)
            wifiRepository.setWifiNetwork(ACTIVE_WIFI)

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.state).isEqualTo(Tile.STATE_ACTIVE)
            assertThat(underTest.state.secondaryLabel).isEqualTo(WIFI_SSID)
        }

    @Test
    fun wifiConnected() =
        testScope.runTest {
            connectivityRepository.defaultConnections.value =
                DefaultConnectionModel(
                    wifi = Wifi(true),
                    isValidated = true,
                )

            wifiRepository.setIsWifiEnabled(true)
            wifiRepository.setWifiNetwork(ACTIVE_WIFI)

            runCurrent()
            looper.processAllMessages()

            assertThat(underTest.state.state).isEqualTo(Tile.STATE_ACTIVE)
            assertThat(underTest.state.secondaryLabel).isEqualTo(WIFI_SSID)
        }

    @Test
    fun secondaryClick_turnsWifiOff() {
        whenever(wifiStateWorker.isWifiEnabled).thenReturn(true)

        underTest.secondaryClick(null)

        verify(wifiStateWorker, times(1)).isWifiEnabled = eq(false)
    }

    @Test
    fun secondaryClick_turnsWifiOn() {
        whenever(wifiStateWorker.isWifiEnabled).thenReturn(false)

        underTest.secondaryClick(null)

        verify(wifiStateWorker, times(1)).isWifiEnabled = eq(true)
    }

    companion object {
        const val WIFI_SSID = "test ssid"
        val ACTIVE_WIFI =
            WifiNetworkModel.Active.of(
                isValidated = true,
                level = 4,
                ssid = WIFI_SSID,
            )
    }
}
