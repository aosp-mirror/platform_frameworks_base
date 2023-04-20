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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModel
import com.android.systemui.statusbar.pipeline.airplane.ui.viewmodel.AirplaneModeViewModelImpl
import com.android.systemui.statusbar.pipeline.shared.ConnectivityConstants
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractorImpl
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiViewModelTest : SysuiTestCase() {

    private lateinit var underTest: WifiViewModel

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var tableLogBuffer: TableLogBuffer
    @Mock private lateinit var connectivityConstants: ConnectivityConstants
    @Mock private lateinit var wifiConstants: WifiConstants
    private lateinit var airplaneModeRepository: FakeAirplaneModeRepository
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor
    private lateinit var airplaneModeViewModel: AirplaneModeViewModel
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        airplaneModeRepository = FakeAirplaneModeRepository()
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        wifiRepository.setIsWifiEnabled(true)
        interactor = WifiInteractorImpl(connectivityRepository, wifiRepository)
        scope = CoroutineScope(IMMEDIATE)
        airplaneModeViewModel = AirplaneModeViewModelImpl(
            AirplaneModeInteractor(
                airplaneModeRepository,
                connectivityRepository,
            ),
            logger,
            scope,
        )

        createAndSetViewModel()
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // See [WifiViewModelIconParameterizedTest] for additional view model tests.

    // Note on testing: [WifiViewModel] exposes 3 different instances of
    // [LocationBasedWifiViewModel]. In practice, these 3 different instances will get the exact
    // same data for icon, activity, etc. flows. So, most of these tests will test just one of the
    // instances. There are also some tests that verify all 3 instances received the same data.

    @Test
    fun wifiIcon_allLocationViewModelsReceiveSameData() = runBlocking(IMMEDIATE) {
        var latestHome: WifiIcon? = null
        val jobHome = underTest
            .home
            .wifiIcon
            .onEach { latestHome = it }
            .launchIn(this)

        var latestKeyguard: WifiIcon? = null
        val jobKeyguard = underTest
            .keyguard
            .wifiIcon
            .onEach { latestKeyguard = it }
            .launchIn(this)

        var latestQs: WifiIcon? = null
        val jobQs = underTest
            .qs
            .wifiIcon
            .onEach { latestQs = it }
            .launchIn(this)

        wifiRepository.setWifiNetwork(
            WifiNetworkModel.Active(
                NETWORK_ID,
                isValidated = true,
                level = 1
            )
        )
        yield()

        assertThat(latestHome).isInstanceOf(WifiIcon.Visible::class.java)
        assertThat(latestHome).isEqualTo(latestKeyguard)
        assertThat(latestKeyguard).isEqualTo(latestQs)

        jobHome.cancel()
        jobKeyguard.cancel()
        jobQs.cancel()
    }

    @Test
    fun activity_showActivityConfigFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(false)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var activityIn: Boolean? = null
        val activityInJob = underTest
            .home
            .isActivityInViewVisible
            .onEach { activityIn = it }
            .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .home
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
            .home
            .isActivityContainerVisible
            .onEach { activityContainer = it }
            .launchIn(this)

        // Verify that on launch, we receive false.
        assertThat(activityIn).isFalse()
        assertThat(activityOut).isFalse()
        assertThat(activityContainer).isFalse()

        activityInJob.cancel()
        activityOutJob.cancel()
        activityContainerJob.cancel()
    }

    @Test
    fun activity_showActivityConfigFalse_noUpdatesReceived() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(false)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var activityIn: Boolean? = null
        val activityInJob = underTest
            .home
            .isActivityInViewVisible
            .onEach { activityIn = it }
            .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .home
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
            .home
            .isActivityContainerVisible
            .onEach { activityContainer = it }
            .launchIn(this)

        // WHEN we update the repo to have activity
        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        // THEN we didn't update to the new activity (because our config is false)
        assertThat(activityIn).isFalse()
        assertThat(activityOut).isFalse()
        assertThat(activityContainer).isFalse()

        activityInJob.cancel()
        activityOutJob.cancel()
        activityContainerJob.cancel()
    }

    @Test
    fun activity_nullSsid_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()

        wifiRepository.setWifiNetwork(WifiNetworkModel.Active(NETWORK_ID, ssid = null, level = 1))

        var activityIn: Boolean? = null
        val activityInJob = underTest
            .home
            .isActivityInViewVisible
            .onEach { activityIn = it }
            .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .home
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
            .home
            .isActivityContainerVisible
            .onEach { activityContainer = it }
            .launchIn(this)

        // WHEN we update the repo to have activity
        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        // THEN we still output false because our network's SSID is null
        assertThat(activityIn).isFalse()
        assertThat(activityOut).isFalse()
        assertThat(activityContainer).isFalse()

        activityInJob.cancel()
        activityOutJob.cancel()
        activityContainerJob.cancel()
    }

    @Test
    fun activity_allLocationViewModelsReceiveSameData() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latestHome: Boolean? = null
        val jobHome = underTest
            .home
            .isActivityInViewVisible
            .onEach { latestHome = it }
            .launchIn(this)

        var latestKeyguard: Boolean? = null
        val jobKeyguard = underTest
            .keyguard
            .isActivityInViewVisible
            .onEach { latestKeyguard = it }
            .launchIn(this)

        var latestQs: Boolean? = null
        val jobQs = underTest
            .qs
            .isActivityInViewVisible
            .onEach { latestQs = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latestHome).isTrue()
        assertThat(latestKeyguard).isTrue()
        assertThat(latestQs).isTrue()

        jobHome.cancel()
        jobKeyguard.cancel()
        jobQs.cancel()
    }

    @Test
    fun activityIn_hasActivityInTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityInViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityIn_hasActivityInFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityInViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityOut_hasActivityOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityOutViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityOut_hasActivityOutFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityOutViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityContainer_hasActivityInTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_hasActivityOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_inAndOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_inAndOutFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(connectivityConstants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .home
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun airplaneSpacer_notAirplaneMode_outputsFalse() = runBlocking(IMMEDIATE) {
        var latest: Boolean? = null
        val job = underTest
            .qs
            .isAirplaneSpacerVisible
            .onEach { latest = it }
            .launchIn(this)

        airplaneModeRepository.setIsAirplaneMode(false)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun airplaneSpacer_airplaneForceHidden_outputsFalse() = runBlocking(IMMEDIATE) {
        var latest: Boolean? = null
        val job = underTest
            .qs
            .isAirplaneSpacerVisible
            .onEach { latest = it }
            .launchIn(this)

        airplaneModeRepository.setIsAirplaneMode(true)
        connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.AIRPLANE))
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun airplaneSpacer_airplaneIconVisible_outputsTrue() = runBlocking(IMMEDIATE) {
        var latest: Boolean? = null
        val job = underTest
            .qs
            .isAirplaneSpacerVisible
            .onEach { latest = it }
            .launchIn(this)

        airplaneModeRepository.setIsAirplaneMode(true)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    private fun createAndSetViewModel() {
        // [WifiViewModel] creates its flows as soon as it's instantiated, and some of those flow
        // creations rely on certain config values that we mock out in individual tests. This method
        // allows tests to create the view model only after those configs are correctly set up.
        underTest = WifiViewModel(
            airplaneModeViewModel,
            connectivityConstants,
            context,
            logger,
            tableLogBuffer,
            interactor,
            scope,
            statusBarPipelineFlags,
            wifiConstants,
        )
    }

    companion object {
        private const val NETWORK_ID = 2
        private val ACTIVE_VALID_WIFI_NETWORK =
            WifiNetworkModel.Active(NETWORK_ID, ssid = "AB", level = 1)
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
