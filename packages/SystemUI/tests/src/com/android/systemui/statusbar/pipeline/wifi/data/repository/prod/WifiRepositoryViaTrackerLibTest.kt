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

package com.android.systemui.statusbar.pipeline.wifi.data.repository.prod

import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiPickerTracker
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.verify

/**
 * Note: Most of these tests are duplicates of [WifiRepositoryImplTest] tests.
 *
 * Any new tests added here may also need to be added to [WifiRepositoryImplTest].
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class WifiRepositoryViaTrackerLibTest : SysuiTestCase() {

    private lateinit var underTest: WifiRepositoryViaTrackerLib

    private val executor = FakeExecutor(FakeSystemClock())
    private val logger = LogBuffer("name", maxSize = 100, logcatEchoTracker = mock())
    private val tableLogger = mock<TableLogBuffer>()
    private val wifiManager =
        mock<WifiManager>().apply { whenever(this.maxSignalLevel).thenReturn(10) }
    private val wifiPickerTrackerFactory = mock<WifiPickerTrackerFactory>()
    private val wifiPickerTracker = mock<WifiPickerTracker>()

    private val callbackCaptor = argumentCaptor<WifiPickerTracker.WifiPickerTrackerCallback>()

    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)

    @Before
    fun setUp() {
        whenever(wifiPickerTrackerFactory.create(any(), capture(callbackCaptor)))
            .thenReturn(wifiPickerTracker)
        underTest = createRepo()
    }

    @Test
    fun isWifiEnabled_enabled_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiEnabled)

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_ENABLED)
            getCallback().onWifiStateChanged()

            assertThat(latest).isTrue()
        }

    @Test
    fun isWifiEnabled_enabling_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiEnabled)

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_ENABLING)
            getCallback().onWifiStateChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiEnabled_disabling_true() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiEnabled)

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_DISABLING)
            getCallback().onWifiStateChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiEnabled_disabled_false() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiEnabled)

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_DISABLED)
            getCallback().onWifiStateChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiEnabled_respondsToUpdates() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiEnabled)
            executor.runAllReady()

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_ENABLED)
            getCallback().onWifiStateChanged()

            assertThat(latest).isTrue()

            whenever(wifiPickerTracker.wifiState).thenReturn(WifiManager.WIFI_STATE_DISABLED)
            getCallback().onWifiStateChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiDefault_initiallyGetsDefault() =
        testScope.runTest { assertThat(underTest.isWifiDefault.value).isFalse() }

    @Test
    fun isWifiDefault_wifiNetwork_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiDefault)

            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isDefaultNetwork).thenReturn(true) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isTrue()
        }

    @Test
    fun isWifiDefault_carrierMerged_isTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiDefault)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isTrue()
        }

    @Test
    fun isWifiDefault_wifiNetworkNotDefault_isFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiDefault)

            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isDefaultNetwork).thenReturn(false) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiDefault_carrierMergedNotDefault_isFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiDefault)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isDefaultNetwork).thenReturn(false)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isFalse()
        }

    @Test
    fun isWifiDefault_noWifiNetwork_isFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.isWifiDefault)

            // First, add a network
            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isDefaultNetwork).thenReturn(true) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isTrue()

            // WHEN the network is lost
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()

            // THEN we update to false
            assertThat(latest).isFalse()
        }

    @Test
    fun wifiNetwork_initiallyGetsDefault() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            assertThat(latest).isEqualTo(WIFI_NETWORK_DEFAULT)
        }

    @Test
    fun wifiNetwork_primaryWifiNetworkAdded_flowHasNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.ssid).thenReturn(SSID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.level).isEqualTo(3)
            assertThat(latestActive.ssid).isEqualTo(SSID)
        }

    @Test
    fun wifiNetwork_isCarrierMerged_flowHasCarrierMerged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            val latestMerged = latest as WifiNetworkModel.CarrierMerged
            assertThat(latestMerged.level).isEqualTo(3)
            // numberOfLevels = maxSignalLevel + 1
        }

    @Test
    fun wifiNetwork_isCarrierMerged_getsMaxSignalLevel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            whenever(wifiManager.maxSignalLevel).thenReturn(5)

            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            val latestMerged = latest as WifiNetworkModel.CarrierMerged
            // numberOfLevels = maxSignalLevel + 1
            assertThat(latestMerged.numberOfLevels).isEqualTo(6)
        }

    /* TODO(b/292534484): Re-enable this test once WifiTrackerLib gives us the subscription ID.
    @Test
    fun wifiNetwork_carrierMergedButInvalidSubId_flowHasInvalid() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiInfo =
                mock<WifiInfo>().apply {
                    whenever(this.isPrimary).thenReturn(true)
                    whenever(this.isCarrierMerged).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
                }

            getNetworkCallback()
                .onCapabilitiesChanged(
                    NETWORK,
                    createWifiNetworkCapabilities(wifiInfo),
                )

            assertThat(latest).isInstanceOf(WifiNetworkModel.Invalid::class.java)
        }

     */

    @Test
    fun wifiNetwork_notValidated_networkNotValidated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.hasInternetAccess()).thenReturn(false)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).isValidated).isFalse()
        }

    @Test
    fun wifiNetwork_validated_networkValidated() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.hasInternetAccess()).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).isValidated).isTrue()
        }

    @Test
    fun wifiNetwork_nonPrimaryWifiNetworkAdded_flowHasNoNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isPrimaryNetwork).thenReturn(false) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEqualTo(WifiNetworkModel.Inactive)
        }

    @Test
    fun wifiNetwork_nonPrimaryCarrierMergedNetworkAdded_flowHasNoNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(false)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEqualTo(WifiNetworkModel.Inactive)
        }

    @Test
    fun wifiNetwork_newPrimaryWifiNetwork_flowHasNewNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            // Start with the original network
            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.ssid).thenReturn("AB")
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.Active).isTrue()
            var latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.level).isEqualTo(3)
            assertThat(latestActive.ssid).isEqualTo("AB")

            // WHEN we update to a new primary network
            val newWifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(4)
                    whenever(this.ssid).thenReturn("CD")
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(newWifiEntry)
            getCallback().onWifiEntriesChanged()

            // THEN we use the new network
            assertThat(latest is WifiNetworkModel.Active).isTrue()
            latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.level).isEqualTo(4)
            assertThat(latestActive.ssid).isEqualTo("CD")
        }

    @Test
    fun wifiNetwork_noCurrentNetwork_networkLost_flowHasNoNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            // WHEN we receive a null network without any networks beforehand
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()

            // THEN there's no crash and we still have no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()
        }

    @Test
    fun wifiNetwork_currentActiveNetworkLost_flowHasNoNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.ssid).thenReturn(SSID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).ssid).isEqualTo(SSID)

            // WHEN we lose our current network
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()

            // THEN we update to no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()
        }

    /** Possible regression test for b/278618530. */
    @Test
    fun wifiNetwork_currentCarrierMergedNetworkLost_flowHasNoNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            assertThat((latest as WifiNetworkModel.CarrierMerged).level).isEqualTo(3)

            // WHEN we lose our current network
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()

            // THEN we update to no network
            assertThat(latest is WifiNetworkModel.Inactive).isTrue()
        }

    /** Regression test for b/244173280. */
    @Test
    fun wifiNetwork_multipleSubscribers_newSubscribersGetCurrentValue() =
        testScope.runTest {
            val latest1 by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(1)
                    whenever(this.ssid).thenReturn(SSID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest1 is WifiNetworkModel.Active).isTrue()
            val latest1Active = latest1 as WifiNetworkModel.Active
            assertThat(latest1Active.level).isEqualTo(1)
            assertThat(latest1Active.ssid).isEqualTo(SSID)

            // WHEN we add a second subscriber after having already emitted a value
            val latest2 by collectLastValue(underTest.wifiNetwork)

            // THEN the second subscribe receives the already-emitted value
            assertThat(latest2 is WifiNetworkModel.Active).isTrue()
            val latest2Active = latest2 as WifiNetworkModel.Active
            assertThat(latest2Active.level).isEqualTo(1)
            assertThat(latest2Active.ssid).isEqualTo(SSID)
        }

    @Test
    fun isWifiConnectedWithValidSsid_inactiveNetwork_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_nonPrimaryNetwork_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isPrimaryNetwork).thenReturn(false) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_carrierMergedNetwork_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    /* TODO(b/292534484): Re-enable this test once WifiTrackerLib gives us the subscription ID.
       @Test
       fun isWifiConnectedWithValidSsid_invalidNetwork_false() =
       testScope.runTest {
           collectLastValue(underTest.wifiNetwork)

           val wifiInfo =
               mock<WifiInfo>().apply {
                   whenever(this.isPrimary).thenReturn(true)
                   whenever(this.isCarrierMerged).thenReturn(true)
                   whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
               }

           getNetworkCallback()
               .onCapabilitiesChanged(
                   NETWORK,
                   createWifiNetworkCapabilities(wifiInfo),
               )
           testScope.runCurrent()

           assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
       }

    */

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_nullSsid_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.ssid).thenReturn(null)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_unknownSsid_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.ssid).thenReturn(UNKNOWN_SSID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_validSsid_true() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.ssid).thenReturn("fakeSsid")
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isTrue()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeToInactive_trueToFalse() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            // Start with active
            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.ssid).thenReturn("fakeSsid")
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isTrue()

            // WHEN the network is lost
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            // THEN the isWifiConnected updates
            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun wifiActivity_callbackGivesNone_activityFlowHasNone() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiActivity)

            getTrafficStateCallback()
                .onStateChanged(WifiManager.TrafficStateCallback.DATA_ACTIVITY_NONE)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = false))
        }

    @Test
    fun wifiActivity_callbackGivesIn_activityFlowHasIn() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiActivity)

            getTrafficStateCallback()
                .onStateChanged(WifiManager.TrafficStateCallback.DATA_ACTIVITY_IN)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = false))
        }

    @Test
    fun wifiActivity_callbackGivesOut_activityFlowHasOut() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiActivity)

            getTrafficStateCallback()
                .onStateChanged(WifiManager.TrafficStateCallback.DATA_ACTIVITY_OUT)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = false, hasActivityOut = true))
        }

    @Test
    fun wifiActivity_callbackGivesInout_activityFlowHasInAndOut() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiActivity)

            getTrafficStateCallback()
                .onStateChanged(WifiManager.TrafficStateCallback.DATA_ACTIVITY_INOUT)

            assertThat(latest)
                .isEqualTo(DataActivityModel(hasActivityIn = true, hasActivityOut = true))
        }

    private fun getCallback(): WifiPickerTracker.WifiPickerTrackerCallback {
        testScope.runCurrent()
        return callbackCaptor.value
    }

    private fun getTrafficStateCallback(): WifiManager.TrafficStateCallback {
        testScope.runCurrent()
        val callbackCaptor = argumentCaptor<WifiManager.TrafficStateCallback>()
        verify(wifiManager).registerTrafficStateCallback(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private fun createRepo(): WifiRepositoryViaTrackerLib {
        return WifiRepositoryViaTrackerLib(
            testScope.backgroundScope,
            executor,
            wifiPickerTrackerFactory,
            wifiManager,
            logger,
            tableLogger,
        )
    }

    private companion object {
        const val SSID = "AB"
    }
}
