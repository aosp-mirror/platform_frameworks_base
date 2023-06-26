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

package com.android.systemui.statusbar.pipeline.wifi.domain.interactor

import android.net.wifi.WifiManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@SmallTest
class WifiInteractorImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiInteractor

    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository

    @Before
    fun setUp() {
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        underTest = WifiInteractorImpl(connectivityRepository, wifiRepository)
    }

    @Test
    fun ssid_unavailableNetwork_outputsNull() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(WifiNetworkModel.Unavailable)

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_inactiveNetwork_outputsNull() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive)

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_carrierMergedNetwork_outputsNull() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(networkId = 1, subscriptionId = 2, level = 1)
            )

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_isPasspointAccessPoint_outputsPasspointName() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 1,
                    isPasspointAccessPoint = true,
                    passpointProviderFriendlyName = "friendly",
                )
            )

            var latest: String? = null
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo("friendly")

            job.cancel()
        }

    @Test
    fun ssid_isOnlineSignUpForPasspoint_outputsPasspointName() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 1,
                    isOnlineSignUpForPasspointAccessPoint = true,
                    passpointProviderFriendlyName = "friendly",
                )
            )

            var latest: String? = null
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo("friendly")

            job.cancel()
        }

    @Test
    fun ssid_unknownSsid_outputsNull() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 1,
                    ssid = WifiManager.UNKNOWN_SSID,
                )
            )

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_validSsid_outputsSsid() =
        runBlocking(IMMEDIATE) {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active(
                    networkId = 1,
                    level = 1,
                    ssid = "MyAwesomeWifiNetwork",
                )
            )

            var latest: String? = null
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo("MyAwesomeWifiNetwork")

            job.cancel()
        }

    @Test
    fun isEnabled_matchesRepoIsEnabled() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isEnabled.onEach { latest = it }.launchIn(this)

            wifiRepository.setIsWifiEnabled(true)
            yield()
            assertThat(latest).isTrue()

            wifiRepository.setIsWifiEnabled(false)
            yield()
            assertThat(latest).isFalse()

            wifiRepository.setIsWifiEnabled(true)
            yield()
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isDefault_matchesRepoIsDefault() =
        runBlocking(IMMEDIATE) {
            var latest: Boolean? = null
            val job = underTest.isDefault.onEach { latest = it }.launchIn(this)

            wifiRepository.setIsWifiDefault(true)
            yield()
            assertThat(latest).isTrue()

            wifiRepository.setIsWifiDefault(false)
            yield()
            assertThat(latest).isFalse()

            wifiRepository.setIsWifiDefault(true)
            yield()
            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun wifiNetwork_matchesRepoWifiNetwork() =
        runBlocking(IMMEDIATE) {
            val wifiNetwork =
                WifiNetworkModel.Active(
                    networkId = 45,
                    isValidated = true,
                    level = 3,
                    ssid = "AB",
                    passpointProviderFriendlyName = "friendly"
                )
            wifiRepository.setWifiNetwork(wifiNetwork)

            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(wifiNetwork)

            job.cancel()
        }

    @Test
    fun activity_matchesRepoWifiActivity() =
        runBlocking(IMMEDIATE) {
            var latest: DataActivityModel? = null
            val job = underTest.activity.onEach { latest = it }.launchIn(this)

            val activity1 = DataActivityModel(hasActivityIn = true, hasActivityOut = true)
            wifiRepository.setWifiActivity(activity1)
            yield()
            assertThat(latest).isEqualTo(activity1)

            val activity2 = DataActivityModel(hasActivityIn = false, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity2)
            yield()
            assertThat(latest).isEqualTo(activity2)

            val activity3 = DataActivityModel(hasActivityIn = true, hasActivityOut = false)
            wifiRepository.setWifiActivity(activity3)
            yield()
            assertThat(latest).isEqualTo(activity3)

            job.cancel()
        }

    @Test
    fun isForceHidden_repoHasWifiHidden_outputsTrue() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.WIFI))

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isForceHidden_repoDoesNotHaveWifiHidden_outputsFalse() =
        runBlocking(IMMEDIATE) {
            connectivityRepository.setForceHiddenIcons(setOf())

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }
}

private val IMMEDIATE = Dispatchers.Main.immediate
