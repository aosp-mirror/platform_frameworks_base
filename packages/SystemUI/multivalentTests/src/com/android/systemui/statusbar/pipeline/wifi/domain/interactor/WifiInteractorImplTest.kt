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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.pipeline.shared.data.model.ConnectivitySlot
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@SmallTest
@RunWith(AndroidJUnit4::class)
@android.platform.test.annotations.EnabledOnRavenwood
class WifiInteractorImplTest : SysuiTestCase() {

    private lateinit var underTest: WifiInteractor

    private lateinit var connectivityRepository: FakeConnectivityRepository
    private lateinit var wifiRepository: FakeWifiRepository

    private val testScope = TestScope()

    @Before
    fun setUp() {
        connectivityRepository = FakeConnectivityRepository()
        wifiRepository = FakeWifiRepository()
        underTest =
            WifiInteractorImpl(connectivityRepository, wifiRepository, testScope.backgroundScope)
    }

    @Test
    fun ssid_unavailableNetwork_outputsNull() =
        testScope.runTest {
            wifiRepository.setWifiNetwork(WifiNetworkModel.Unavailable)

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_inactiveNetwork_outputsNull() =
        testScope.runTest {
            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive())

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_carrierMergedNetwork_outputsNull() =
        testScope.runTest {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged.of(subscriptionId = 2, level = 1)
            )

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_unknownSsid_outputsNull() =
        testScope.runTest {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(
                    level = 1,
                    ssid = WifiManager.UNKNOWN_SSID,
                )
            )

            var latest: String? = "default"
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isNull()

            job.cancel()
        }

    @Test
    fun ssid_validSsid_outputsSsid() =
        testScope.runTest {
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(
                    level = 1,
                    ssid = "MyAwesomeWifiNetwork",
                )
            )

            var latest: String? = null
            val job = underTest.ssid.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isEqualTo("MyAwesomeWifiNetwork")

            job.cancel()
        }

    @Test
    fun isEnabled_matchesRepoIsEnabled() =
        testScope.runTest {
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
        testScope.runTest {
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
        testScope.runTest {
            val wifiNetwork =
                WifiNetworkModel.Active.of(
                    isValidated = true,
                    level = 3,
                    ssid = "AB",
                )
            wifiRepository.setWifiNetwork(wifiNetwork)

            var latest: WifiNetworkModel? = null
            val job = underTest.wifiNetwork.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isEqualTo(wifiNetwork)

            job.cancel()
        }

    @Test
    fun activity_matchesRepoWifiActivity() =
        testScope.runTest {
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
        testScope.runTest {
            connectivityRepository.setForceHiddenIcons(setOf(ConnectivitySlot.WIFI))

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isTrue()

            job.cancel()
        }

    @Test
    fun isForceHidden_repoDoesNotHaveWifiHidden_outputsFalse() =
        testScope.runTest {
            connectivityRepository.setForceHiddenIcons(setOf())

            var latest: Boolean? = null
            val job = underTest.isForceHidden.onEach { latest = it }.launchIn(this)
            runCurrent()

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun areNetworksAvailable_noneActive_noResults() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areNetworksAvailable)

            wifiRepository.wifiScanResults.value = emptyList()
            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive())

            assertThat(latest).isFalse()
        }

    @Test
    fun areNetworksAvailable_noneActive_nonEmptyResults() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areNetworksAvailable)

            wifiRepository.wifiScanResults.value =
                listOf(
                    WifiScanEntry(ssid = "ssid 1"),
                    WifiScanEntry(ssid = "ssid 2"),
                    WifiScanEntry(ssid = "ssid 3"),
                )

            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive())

            assertThat(latest).isTrue()
        }

    @Test
    fun areNetworksAvailable_activeNetwork_resultsIncludeOtherNetworks() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areNetworksAvailable)

            wifiRepository.wifiScanResults.value =
                listOf(
                    WifiScanEntry(ssid = "ssid 1"),
                    WifiScanEntry(ssid = "ssid 2"),
                    WifiScanEntry(ssid = "ssid 3"),
                )

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(
                    ssid = "ssid 2",
                    level = 2,
                )
            )

            assertThat(latest).isTrue()
        }

    @Test
    fun areNetworksAvailable_activeNetwork_onlyResultIsTheActiveNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.areNetworksAvailable)

            wifiRepository.wifiScanResults.value =
                listOf(
                    WifiScanEntry(ssid = "ssid 2"),
                )

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.Active.of(
                    ssid = "ssid 2",
                    level = 2,
                )
            )

            assertThat(latest).isFalse()
        }
}
