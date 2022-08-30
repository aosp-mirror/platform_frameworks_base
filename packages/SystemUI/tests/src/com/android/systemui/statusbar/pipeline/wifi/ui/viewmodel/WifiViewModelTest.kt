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
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.domain.interactor.WifiInteractor
import com.android.systemui.statusbar.pipeline.wifi.shared.WifiConstants
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

        underTest = WifiViewModel(
            statusBarPipelineFlags,
            constants,
            context,
            logger,
            interactor
        )
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
    fun activityInVisible_showActivityConfigFalse_outputsFalse() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(false)
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Verify that on launch, we receive a false.
        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityInVisible_showActivityConfigFalse_noUpdatesReceived() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(false)
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Update the repo to have activityIn
        wifiRepository.setWifiActivity(
            WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        )
        yield()

        // Verify that we didn't update to activityIn=true (because our config is false)
        assertThat(latest).isFalse()

        job.cancel()
    }

    @Test
    fun activityInVisible_showActivityConfigTrue_outputsUpdate() = runBlocking(IMMEDIATE) {
        whenever(constants.shouldShowActivityConfig).thenReturn(true)
        wifiRepository.setWifiNetwork(ACTIVE_VALID_WIFI_NETWORK)

        var latest: Boolean? = null
        val job = underTest
                .isActivityInVisible
                .onEach { latest = it }
                .launchIn(this)

        // Update the repo to have activityIn
        wifiRepository.setWifiActivity(
            WifiActivityModel(hasActivityIn = true, hasActivityOut = false)
        )
        yield()

        // Verify that we updated to activityIn=true
        assertThat(latest).isTrue()

        job.cancel()
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
