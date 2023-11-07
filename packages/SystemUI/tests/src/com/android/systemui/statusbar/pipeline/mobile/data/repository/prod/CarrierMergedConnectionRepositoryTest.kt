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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.telephony.TelephonyManager
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
class CarrierMergedConnectionRepositoryTest : SysuiTestCase() {

    private lateinit var underTest: CarrierMergedConnectionRepository

    private lateinit var wifiRepository: FakeWifiRepository
    @Mock private lateinit var logger: TableLogBuffer
    @Mock private lateinit var telephonyManager: TelephonyManager

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(telephonyManager.subscriptionId).thenReturn(SUB_ID)
        whenever(telephonyManager.simOperatorName).thenReturn("")

        wifiRepository = FakeWifiRepository()

        underTest =
            CarrierMergedConnectionRepository(
                SUB_ID,
                logger,
                telephonyManager,
                testScope.backgroundScope.coroutineContext,
                testScope.backgroundScope,
                wifiRepository,
            )
    }

    @Test
    fun inactiveWifi_isDefault() =
        testScope.runTest {
            var latestConnState: DataConnectionState? = null
            var latestNetType: ResolvedNetworkType? = null

            val dataJob =
                underTest.dataConnectionState.onEach { latestConnState = it }.launchIn(this)
            val netJob = underTest.resolvedNetworkType.onEach { latestNetType = it }.launchIn(this)

            wifiRepository.setWifiNetwork(WifiNetworkModel.Inactive)

            assertThat(latestConnState).isEqualTo(DataConnectionState.Disconnected)
            assertThat(latestNetType).isNotEqualTo(ResolvedNetworkType.CarrierMergedNetworkType)

            dataJob.cancel()
            netJob.cancel()
        }

    @Test
    fun activeWifi_isDefault() =
        testScope.runTest {
            var latestConnState: DataConnectionState? = null
            var latestNetType: ResolvedNetworkType? = null

            val dataJob =
                underTest.dataConnectionState.onEach { latestConnState = it }.launchIn(this)
            val netJob = underTest.resolvedNetworkType.onEach { latestNetType = it }.launchIn(this)

            wifiRepository.setWifiNetwork(WifiNetworkModel.Active(networkId = NET_ID, level = 1))

            assertThat(latestConnState).isEqualTo(DataConnectionState.Disconnected)
            assertThat(latestNetType).isNotEqualTo(ResolvedNetworkType.CarrierMergedNetworkType)

            dataJob.cancel()
            netJob.cancel()
        }

    @Test
    fun carrierMergedWifi_isValidAndFieldsComeFromWifiNetwork() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.primaryLevel.onEach { latest = it }.launchIn(this)

            wifiRepository.setIsWifiEnabled(true)
            wifiRepository.setIsWifiDefault(true)

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 3,
                )
            )

            assertThat(latest).isEqualTo(3)

            job.cancel()
        }

    @Test
    fun activity_comesFromWifiActivity() =
        testScope.runTest {
            var latest: DataActivityModel? = null
            val job = underTest.dataActivityDirection.onEach { latest = it }.launchIn(this)

            wifiRepository.setIsWifiEnabled(true)
            wifiRepository.setIsWifiDefault(true)
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 3,
                )
            )
            wifiRepository.setWifiActivity(
                DataActivityModel(
                    hasActivityIn = true,
                    hasActivityOut = false,
                )
            )

            assertThat(latest!!.hasActivityIn).isTrue()
            assertThat(latest!!.hasActivityOut).isFalse()

            wifiRepository.setWifiActivity(
                DataActivityModel(
                    hasActivityIn = false,
                    hasActivityOut = true,
                )
            )

            assertThat(latest!!.hasActivityIn).isFalse()
            assertThat(latest!!.hasActivityOut).isTrue()

            job.cancel()
        }

    @Test
    fun carrierMergedWifi_wrongSubId_isDefault() =
        testScope.runTest {
            var latestLevel: Int? = null
            var latestType: ResolvedNetworkType? = null
            val levelJob = underTest.primaryLevel.onEach { latestLevel = it }.launchIn(this)
            val typeJob = underTest.resolvedNetworkType.onEach { latestType = it }.launchIn(this)

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID + 10,
                    level = 3,
                )
            )

            assertThat(latestLevel).isNotEqualTo(3)
            assertThat(latestType).isNotEqualTo(ResolvedNetworkType.CarrierMergedNetworkType)

            levelJob.cancel()
            typeJob.cancel()
        }

    // This scenario likely isn't possible, but write a test for it anyway
    @Test
    fun carrierMergedButNotEnabled_isDefault() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.primaryLevel.onEach { latest = it }.launchIn(this)

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 3,
                )
            )
            wifiRepository.setIsWifiEnabled(false)

            assertThat(latest).isNotEqualTo(3)

            job.cancel()
        }

    // This scenario likely isn't possible, but write a test for it anyway
    @Test
    fun carrierMergedButWifiNotDefault_isDefault() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.primaryLevel.onEach { latest = it }.launchIn(this)

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 3,
                )
            )
            wifiRepository.setIsWifiDefault(false)

            assertThat(latest).isNotEqualTo(3)

            job.cancel()
        }

    @Test
    fun numberOfLevels_comesFromCarrierMerged() =
        testScope.runTest {
            var latest: Int? = null
            val job = underTest.numberOfLevels.onEach { latest = it }.launchIn(this)

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 1,
                    numberOfLevels = 6,
                )
            )

            assertThat(latest).isEqualTo(6)

            job.cancel()
        }

    @Test
    fun dataEnabled_matchesWifiEnabled() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.dataEnabled.onEach { latest = it }.launchIn(this)

            wifiRepository.setIsWifiEnabled(true)
            assertThat(latest).isTrue()

            wifiRepository.setIsWifiEnabled(false)
            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun cdmaRoaming_alwaysFalse() =
        testScope.runTest {
            var latest: Boolean? = null
            val job = underTest.cdmaRoaming.onEach { latest = it }.launchIn(this)

            assertThat(latest).isFalse()

            job.cancel()
        }

    @Test
    fun networkName_usesSimOperatorNameAsInitial() =
        testScope.runTest {
            whenever(telephonyManager.simOperatorName).thenReturn("Test SIM name")

            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(NetworkNameModel.SimDerived("Test SIM name"))

            job.cancel()
        }

    @Test
    fun networkName_updatesOnNetworkUpdate() =
        testScope.runTest {
            whenever(telephonyManager.simOperatorName).thenReturn("Test SIM name")

            var latest: NetworkNameModel? = null
            val job = underTest.networkName.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(NetworkNameModel.SimDerived("Test SIM name"))

            whenever(telephonyManager.simOperatorName).thenReturn("New SIM name")
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId = NET_ID,
                    subscriptionId = SUB_ID,
                    level = 3,
                )
            )

            assertThat(latest).isEqualTo(NetworkNameModel.SimDerived("New SIM name"))

            job.cancel()
        }

    @Test
    fun isAllowedDuringAirplaneMode_alwaysTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isAllowedDuringAirplaneMode)

            assertThat(latest).isTrue()
        }

    private companion object {
        const val SUB_ID = 123
        const val NET_ID = 456
    }
}
