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
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_CONNECTION_STRENGTH
import com.android.settingslib.AccessibilityContentDescriptions.WIFI_NO_CONNECTION
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_FULL_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_INTERNET_ICONS
import com.android.systemui.statusbar.connectivity.WifiIcons.WIFI_NO_NETWORK
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel.Companion.NO_INTERNET
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class WifiViewModelTest : SysuiTestCase() {

    private lateinit var underTest: WifiViewModel

    @Mock private lateinit var statusBarPipelineFlags: StatusBarPipelineFlags
    @Mock private lateinit var logger: ConnectivityPipelineLogger
    @Mock private lateinit var constants: WifiConstants
    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository
    private lateinit var interactor: WifiInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        interactor = WifiInteractor(connectivityRepository, wifiRepository)
        createAndSetViewModel()
    }

    @Test
    fun wifiIcon_forceHidden_outputsNull() = runBlocking(IMMEDIATE) {
        connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.WIFI))
        wifiRepository.setWifiNetwork(WifiNetworkModel.Active(NETWORK_ID, level = 2))

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isNull()

        job.cancel()
    }

    @Test
    fun wifiIcon_notForceHidden_outputsVisible() = runBlocking(IMMEDIATE) {
        connectivityRepository.setForceHiddenIcons(setOf())
        wifiRepository.setWifiNetwork(WifiNetworkModel.Active(NETWORK_ID, level = 2))

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isInstanceOf(Icon.Resource::class.java)

        job.cancel()
    }

    @Test
    fun wifiIcon_inactiveNetwork_outputsNoNetworkIcon() = runBlocking(IMMEDIATE) {
        wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive)

        var latest: Icon? = null
        val job = underTest
                .wifiIcon
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isInstanceOf(Icon.Resource::class.java)
        val icon = latest as Icon.Resource
        assertThat(icon.res).isEqualTo(WIFI_NO_NETWORK)
        assertThat(icon.contentDescription?.getAsString())
            .contains(context.getString(WIFI_NO_CONNECTION))
        assertThat(icon.contentDescription?.getAsString())
            .contains(context.getString(NO_INTERNET))

        job.cancel()
    }

    @Test
    fun wifiIcon_carrierMergedNetwork_outputsNull() = runBlocking(IMMEDIATE) {
        wifiRepository.setWifiNetwork(WifiNetworkModel.CarrierMerged)

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isNull()

        job.cancel()
    }

    @Test
    fun wifiIcon_isActiveNullLevel_outputsNull() = runBlocking(IMMEDIATE) {
        wifiRepository.setWifiNetwork(WifiNetworkModel.Active(NETWORK_ID, level = null))

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isNull()

        job.cancel()
    }

    @Test
    fun wifiIcon_isActiveAndValidated_level1_outputsFull1Icon() = runBlocking(IMMEDIATE) {
        val level = 1

        wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                        NETWORK_ID,
                        isValidated = true,
                        level = level
                )
        )

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isInstanceOf(Icon.Resource::class.java)
        val icon = latest as Icon.Resource
        assertThat(icon.res).isEqualTo(WIFI_FULL_ICONS[level])
        assertThat(icon.contentDescription?.getAsString())
            .contains(context.getString(WIFI_CONNECTION_STRENGTH[level]))
        assertThat(icon.contentDescription?.getAsString())
            .doesNotContain(context.getString(NO_INTERNET))

        job.cancel()
    }

    @Test
    fun wifiIcon_isActiveAndNotValidated_level4_outputsEmpty4Icon() = runBlocking(IMMEDIATE) {
        val level = 4

        wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                        NETWORK_ID,
                        isValidated = false,
                        level = level
                )
        )

        var latest: Icon? = null
        val job = underTest
            .wifiIcon
            .onEach { latest = it }
            .launchIn(this)

        assertThat(latest).isInstanceOf(Icon.Resource::class.java)
        val icon = latest as Icon.Resource
        assertThat(icon.res).isEqualTo(WIFI_NO_INTERNET_ICONS[level])
        assertThat(icon.contentDescription?.getAsString())
            .contains(context.getString(WIFI_CONNECTION_STRENGTH[level]))
        assertThat(icon.contentDescription?.getAsString())
            .contains(context.getString(NO_INTERNET))

        job.cancel()
    }

    @Test
    fun activity_showActivityConfigFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(false)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var activityIn: Boolean? = null
        val activityInJob = underTest
                .isActivityInViewVisible
                .onEach { activityIn = it }
                .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
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
        whenever(constants.shouldShowActivityConfig).thenReturn(false)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var activityIn: Boolean? = null
        val activityInJob = underTest
            .isActivityInViewVisible
            .onEach { activityIn = it }
            .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
            .isActivityContainerVisible
            .onEach { activityContainer = it }
            .launchIn(this)

        // WHEN we update the repo to have activity
        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = true)
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
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()

        wifiRepository.setWifiNetwork(WifiNetworkModel.Active(NETWORK_ID, ssid = null))

        var activityIn: Boolean? = null
        val activityInJob = underTest
            .isActivityInViewVisible
            .onEach { activityIn = it }
            .launchIn(this)

        var activityOut: Boolean? = null
        val activityOutJob = underTest
            .isActivityOutViewVisible
            .onEach { activityOut = it }
            .launchIn(this)

        var activityContainer: Boolean? = null
        val activityContainerJob = underTest
            .isActivityContainerVisible
            .onEach { activityContainer = it }
            .launchIn(this)

        // WHEN we update the repo to have activity
        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = true)
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
    fun activityIn_hasActivityInTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityInViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityIn_hasActivityInFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityInViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityOut_hasActivityOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityOutViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityOut_hasActivityOutFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityOutViewVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityContainer_hasActivityInTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_hasActivityOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = false, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_inAndOutTrue_outputsTrue() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = true, hasActivityOut = true)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isTrue()

        job.cancel()
    }

    @Test
    fun activityContainer_inAndOutFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        createAndSetViewModel()
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
            .isActivityContainerVisible
            .onEach { latest = it }
            .launchIn(this)

        val activity = WifiActivityModel(hasActivityIn = false, hasActivityOut = false)
        wifiRepository.setWifiActivity(activity)
        yield()

        assertThat(latest).isFalse()

        job.cancel()
    }

    private fun createAndSetViewModel() {
        // [WifiViewModel] creates its flows as soon as it's instantiated, and some of those flow
        // creations rely on certain config values that we mock out in individual tests. This method
        // allows tests to create the view model only after those configs are correctly set up.
        underTest = WifiViewModel(
            constants,
            context,
            logger,
            interactor,
            statusBarPipelineFlags,
        )
    }

    private fun ContentDescription.getAsString(): String? {
        return when (this) {
            is ContentDescription.Loaded -> this.description
            is ContentDescription.Resource -> context.getString(this.res)
        }
    }

    companion object {
        private const val NETWORK_ID = 2
        private val ACTIVE_VALID_WIFI_NETWORK = WifiNetworkModel.Active(NETWORK_ID, ssid = "AB")
    }
}

private val IMMEDIATE = Dispatchers.Main.immediate
