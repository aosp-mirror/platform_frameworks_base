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

import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.UNKNOWN_SSID
import android.net.wifi.sharedconnectivity.app.NetworkProviderInfo
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.connectivity.WifiPickerTrackerFactory
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.prod.WifiRepositoryImpl.Companion.WIFI_NETWORK_DEFAULT
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiScanEntry
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wifitrackerlib.HotspotNetworkEntry
import com.android.wifitrackerlib.HotspotNetworkEntry.DeviceType
import com.android.wifitrackerlib.MergedCarrierEntry
import com.android.wifitrackerlib.WifiEntry
import com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_MAX
import com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_MIN
import com.android.wifitrackerlib.WifiEntry.WIFI_LEVEL_UNREACHABLE
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

    // Using lazy means that the class will only be constructed once it's fetched. Because the
    // repository internally sets some values on construction, we need to set up some test
    // parameters (like feature flags) *before* construction. Using lazy allows us to do that setup
    // inside each test case without needing to manually recreate the repository.
    private val underTest: WifiRepositoryViaTrackerLib by lazy {
        WifiRepositoryViaTrackerLib(
            featureFlags,
            testScope.backgroundScope,
            executor,
            dispatcher,
            wifiPickerTrackerFactory,
            wifiManager,
            logger,
            tableLogger,
        )
    }

    private val executor = FakeExecutor(FakeSystemClock())
    private val logger = LogBuffer("name", maxSize = 100, logcatEchoTracker = mock())
    private val featureFlags = FakeFeatureFlags()
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
        featureFlags.set(Flags.INSTANT_TETHER, false)
        featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, false)
        whenever(wifiPickerTrackerFactory.create(any(), capture(callbackCaptor), any()))
            .thenReturn(wifiPickerTracker)
    }

    @Test
    fun wifiPickerTrackerCreation_scansDisabled() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)
            testScope.runCurrent()

            verify(wifiPickerTracker).disableScanning()
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

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
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

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isDefaultNetwork).thenReturn(false)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
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
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.level).isEqualTo(3)
            assertThat(latestActive.ssid).isEqualTo(TITLE)
        }

    @Test
    fun accessPointInfo_alwaysFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.Active).isTrue()
            val latestActive = latest as WifiNetworkModel.Active
            assertThat(latestActive.isPasspointAccessPoint).isFalse()
            assertThat(latestActive.isOnlineSignUpForPasspointAccessPoint).isFalse()
            assertThat(latestActive.passpointProviderFriendlyName).isNull()
        }

    @Test
    fun wifiNetwork_unreachableLevel_inactiveNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(WIFI_LEVEL_UNREACHABLE)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEqualTo(WifiNetworkModel.Inactive)
        }

    @Test
    fun wifiNetwork_levelTooHigh_inactiveNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(WIFI_LEVEL_MAX + 1)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEqualTo(WifiNetworkModel.Inactive)
        }

    @Test
    fun wifiNetwork_levelTooLow_inactiveNetwork() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(WIFI_LEVEL_MIN - 1)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEqualTo(WifiNetworkModel.Inactive)
        }

    @Test
    fun wifiNetwork_levelIsMax_activeNetworkWithMaxLevel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(WIFI_LEVEL_MAX)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isInstanceOf(WifiNetworkModel.Active::class.java)
            assertThat((latest as WifiNetworkModel.Active).level).isEqualTo(WIFI_LEVEL_MAX)
        }

    @Test
    fun wifiNetwork_levelIsMin_activeNetworkWithMinLevel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(WIFI_LEVEL_MIN)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest).isInstanceOf(WifiNetworkModel.Active::class.java)
            assertThat((latest as WifiNetworkModel.Active).level).isEqualTo(WIFI_LEVEL_MIN)
        }

    @Test
    fun wifiNetwork_notHotspot_none() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply { whenever(this.isPrimaryNetwork).thenReturn(true) }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.NONE)
        }

    @Test
    fun wifiNetwork_hotspot_unknown() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_UNKNOWN)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.UNKNOWN)
        }

    @Test
    fun wifiNetwork_hotspot_phone() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_PHONE)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.PHONE)
        }

    @Test
    fun wifiNetwork_hotspot_tablet() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_TABLET)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.TABLET)
        }

    @Test
    fun wifiNetwork_hotspot_laptop() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_LAPTOP)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.LAPTOP)
        }

    @Test
    fun wifiNetwork_hotspot_watch() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_WATCH)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.WATCH)
        }

    @Test
    fun wifiNetwork_hotspot_auto() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_AUTO)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.AUTO)
        }

    @Test
    fun wifiNetwork_hotspot_invalid() =
        testScope.runTest {
            featureFlags.set(Flags.INSTANT_TETHER, true)
            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(1234)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.INVALID)
        }

    @Test
    fun wifiNetwork_hotspot_flagOff_valueNotUsed() =
        testScope.runTest {
            // WHEN the flag is off
            featureFlags.set(Flags.INSTANT_TETHER, false)

            val latest by collectLastValue(underTest.wifiNetwork)

            val wifiEntry = createHotspotWithType(NetworkProviderInfo.DEVICE_TYPE_WATCH)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            // THEN NONE is always used, even if the wifi entry does have a hotspot device type
            assertThat((latest as WifiNetworkModel.Active).hotspotDeviceType)
                .isEqualTo(WifiNetworkModel.HotspotDeviceType.NONE)
        }

    @Test
    fun wifiNetwork_isCarrierMerged_flowHasCarrierMerged() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.subscriptionId).thenReturn(567)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            val latestMerged = latest as WifiNetworkModel.CarrierMerged
            assertThat(latestMerged.level).isEqualTo(3)
            assertThat(latestMerged.subscriptionId).isEqualTo(567)
        }

    @Test
    fun wifiNetwork_isCarrierMerged_getsMaxSignalLevel() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            whenever(wifiManager.maxSignalLevel).thenReturn(5)

            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            val latestMerged = latest as WifiNetworkModel.CarrierMerged
            // numberOfLevels = maxSignalLevel + 1
            assertThat(latestMerged.numberOfLevels).isEqualTo(6)
        }

    @Test
    fun wifiNetwork_carrierMergedButInvalidSubId_flowHasInvalid() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)

            getCallback().onWifiEntriesChanged()

            assertThat(latest).isInstanceOf(WifiNetworkModel.Invalid::class.java)
        }

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

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(false)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
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
                    whenever(this.title).thenReturn("AB")
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
                    whenever(this.title).thenReturn("CD")
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
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat((latest as WifiNetworkModel.Active).ssid).isEqualTo(TITLE)

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

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
            assertThat((latest as WifiNetworkModel.CarrierMerged).level).isEqualTo(3)

            // WHEN we lose our current network
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(null)
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
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()

            assertThat(latest1 is WifiNetworkModel.Active).isTrue()
            val latest1Active = latest1 as WifiNetworkModel.Active
            assertThat(latest1Active.level).isEqualTo(1)
            assertThat(latest1Active.ssid).isEqualTo(TITLE)

            // WHEN we add a second subscriber after having already emitted a value
            val latest2 by collectLastValue(underTest.wifiNetwork)

            // THEN the second subscribe receives the already-emitted value
            assertThat(latest2 is WifiNetworkModel.Active).isTrue()
            val latest2Active = latest2 as WifiNetworkModel.Active
            assertThat(latest2Active.level).isEqualTo(1)
            assertThat(latest2Active.ssid).isEqualTo(TITLE)
        }

    @Test
    fun wifiNetwork_carrierMerged_default_usesCarrierMergedInfo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.isDefaultNetwork).thenReturn(true)
                }
            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(1)
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)

            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.CarrierMerged).isTrue()
        }

    @Test
    fun wifiNetwork_carrierMerged_notDefault_usesConnectedInfo() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiNetwork)

            val mergedEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(3)
                    whenever(this.isDefaultNetwork).thenReturn(false)
                }
            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(1)
                    whenever(this.title).thenReturn(TITLE)
                }
            whenever(wifiPickerTracker.mergedCarrierEntry).thenReturn(mergedEntry)
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)

            getCallback().onWifiEntriesChanged()

            assertThat(latest is WifiNetworkModel.Active).isTrue()
        }

    @Test
    fun secondaryNetworks_activeEntriesEmpty_isEmpty() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf())

            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEmpty()
        }

    @Test
    fun secondaryNetworks_oneActiveEntry_hasOne() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val wifiEntry = mock<WifiEntry>()
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(wifiEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(1)
        }

    @Test
    fun secondaryNetworks_multipleActiveEntries_hasMultiple() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val wifiEntry1 = mock<WifiEntry>()
            val wifiEntry2 = mock<WifiEntry>()
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(wifiEntry1, wifiEntry2))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(2)
        }

    @Test
    fun secondaryNetworks_mapsToInactive() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val inactiveEntry =
                mock<WifiEntry>().apply { whenever(this.level).thenReturn(WIFI_LEVEL_UNREACHABLE) }
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(inactiveEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(WifiNetworkModel.Inactive::class.java)
        }

    @Test
    fun secondaryNetworks_mapsToActive() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val activeEntry = mock<WifiEntry>().apply { whenever(this.level).thenReturn(2) }
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(activeEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(WifiNetworkModel.Active::class.java)
            assertThat((latest!![0] as WifiNetworkModel.Active).level).isEqualTo(2)
        }

    @Test
    fun secondaryNetworks_mapsToCarrierMerged() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val carrierMergedEntry =
                mock<MergedCarrierEntry>().apply { whenever(this.level).thenReturn(3) }
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(carrierMergedEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(1)
            assertThat(latest!![0]).isInstanceOf(WifiNetworkModel.CarrierMerged::class.java)
            assertThat((latest!![0] as WifiNetworkModel.CarrierMerged).level).isEqualTo(3)
        }

    @Test
    fun secondaryNetworks_mapsMultipleInOrder() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val activeEntry = mock<WifiEntry>().apply { whenever(this.level).thenReturn(2) }
            val carrierMergedEntry =
                mock<MergedCarrierEntry>().apply { whenever(this.level).thenReturn(3) }
            whenever(wifiPickerTracker.activeWifiEntries)
                .thenReturn(listOf(activeEntry, carrierMergedEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest!![0]).isInstanceOf(WifiNetworkModel.Active::class.java)
            assertThat((latest!![0] as WifiNetworkModel.Active).level).isEqualTo(2)
            assertThat(latest!![1]).isInstanceOf(WifiNetworkModel.CarrierMerged::class.java)
            assertThat((latest!![1] as WifiNetworkModel.CarrierMerged).level).isEqualTo(3)
        }

    @Test
    fun secondaryNetworks_filtersOutConnectedEntry() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val connectedEntry = mock<WifiEntry>().apply { whenever(this.level).thenReturn(1) }
            val secondaryEntry1 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(2) }
            val secondaryEntry2 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(3) }
            // WHEN the active list has both a primary and secondary networks
            whenever(wifiPickerTracker.activeWifiEntries)
                .thenReturn(listOf(connectedEntry, secondaryEntry1, secondaryEntry2))
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(connectedEntry)

            getCallback().onWifiEntriesChanged()

            // THEN only the secondary networks are included
            assertThat(latest).hasSize(2)
            assertThat((latest!![0] as WifiNetworkModel.Active).level).isEqualTo(2)
            assertThat((latest!![1] as WifiNetworkModel.Active).level).isEqualTo(3)
        }

    @Test
    fun secondaryNetworks_noConnectedEntry_hasAllActiveEntries() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val secondaryEntry1 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(2) }
            val secondaryEntry2 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(3) }
            whenever(wifiPickerTracker.activeWifiEntries)
                .thenReturn(listOf(secondaryEntry1, secondaryEntry2))
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(null)

            getCallback().onWifiEntriesChanged()

            assertThat(latest).hasSize(2)
            assertThat((latest!![0] as WifiNetworkModel.Active).level).isEqualTo(2)
            assertThat((latest!![1] as WifiNetworkModel.Active).level).isEqualTo(3)
        }

    @Test
    fun secondaryNetworks_filtersOutPrimaryNetwork() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, true)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val primaryEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.level).thenReturn(1)
                }
            val secondaryEntry1 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(2) }
            val secondaryEntry2 = mock<WifiEntry>().apply { whenever(this.level).thenReturn(3) }
            // WHEN the active list has both a primary and secondary networks
            whenever(wifiPickerTracker.activeWifiEntries)
                .thenReturn(listOf(secondaryEntry1, primaryEntry, secondaryEntry2))

            getCallback().onWifiEntriesChanged()

            // THEN only the secondary networks are included
            assertThat(latest).hasSize(2)
            assertThat((latest!![0] as WifiNetworkModel.Active).level).isEqualTo(2)
            assertThat((latest!![1] as WifiNetworkModel.Active).level).isEqualTo(3)
        }

    @Test
    fun secondaryNetworks_flagOff_noNetworks() =
        testScope.runTest {
            featureFlags.set(Flags.WIFI_SECONDARY_NETWORKS, false)
            val latest by collectLastValue(underTest.secondaryNetworks)

            val wifiEntry = mock<WifiEntry>()
            whenever(wifiPickerTracker.activeWifiEntries).thenReturn(listOf(wifiEntry))

            getCallback().onWifiEntriesChanged()

            assertThat(latest).isEmpty()
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

    @Test
    fun isWifiConnectedWithValidSsid_invalidNetwork_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<MergedCarrierEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.subscriptionId).thenReturn(INVALID_SUBSCRIPTION_ID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_nullTitle_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.title).thenReturn(null)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_unknownTitle_false() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.title).thenReturn(UNKNOWN_SSID)
                }
            whenever(wifiPickerTracker.connectedWifiEntry).thenReturn(wifiEntry)
            getCallback().onWifiEntriesChanged()
            testScope.runCurrent()

            assertThat(underTest.isWifiConnectedWithValidSsid()).isFalse()
        }

    @Test
    fun isWifiConnectedWithValidSsid_activeNetwork_validTitle_true() =
        testScope.runTest {
            collectLastValue(underTest.wifiNetwork)

            val wifiEntry =
                mock<WifiEntry>().apply {
                    whenever(this.isPrimaryNetwork).thenReturn(true)
                    whenever(this.title).thenReturn("fakeSsid")
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
                    whenever(this.title).thenReturn("fakeSsid")
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

    @Test
    fun wifiScanResults_containsSsidList() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiScanResults)

            val scanResults =
                listOf(
                    ScanResult().also { it.SSID = "ssid 1" },
                    ScanResult().also { it.SSID = "ssid 2" },
                    ScanResult().also { it.SSID = "ssid 3" },
                    ScanResult().also { it.SSID = "ssid 4" },
                    ScanResult().also { it.SSID = "ssid 5" },
                )
            whenever(wifiManager.scanResults).thenReturn(scanResults)
            getScanResultsCallback().onScanResultsAvailable()

            val expected =
                listOf(
                    WifiScanEntry(ssid = "ssid 1"),
                    WifiScanEntry(ssid = "ssid 2"),
                    WifiScanEntry(ssid = "ssid 3"),
                    WifiScanEntry(ssid = "ssid 4"),
                    WifiScanEntry(ssid = "ssid 5"),
                )

            assertThat(latest).isEqualTo(expected)
        }

    @Test
    fun wifiScanResults_updates() =
        testScope.runTest {
            val latest by collectLastValue(underTest.wifiScanResults)

            var scanResults =
                listOf(
                    ScanResult().also { it.SSID = "ssid 1" },
                    ScanResult().also { it.SSID = "ssid 2" },
                    ScanResult().also { it.SSID = "ssid 3" },
                    ScanResult().also { it.SSID = "ssid 4" },
                    ScanResult().also { it.SSID = "ssid 5" },
                )
            whenever(wifiManager.scanResults).thenReturn(scanResults)
            getScanResultsCallback().onScanResultsAvailable()

            // New scan representing no results
            scanResults = listOf()
            whenever(wifiManager.scanResults).thenReturn(scanResults)
            getScanResultsCallback().onScanResultsAvailable()

            assertThat(latest).isEmpty()
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

    private fun createHotspotWithType(@DeviceType type: Int): HotspotNetworkEntry {
        return mock<HotspotNetworkEntry>().apply {
            whenever(this.isPrimaryNetwork).thenReturn(true)
            whenever(this.deviceType).thenReturn(type)
        }
    }

    private fun getScanResultsCallback(): WifiManager.ScanResultsCallback {
        testScope.runCurrent()
        val callbackCaptor = argumentCaptor<WifiManager.ScanResultsCallback>()
        verify(wifiManager).registerScanResultsCallback(any(), callbackCaptor.capture())
        return callbackCaptor.value!!
    }

    private companion object {
        const val TITLE = "AB"
    }
}
