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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.demo

import android.telephony.TelephonyManager.DATA_ACTIVITY_INOUT
import android.telephony.TelephonyManager.DATA_ACTIVITY_NONE
import android.telephony.TelephonyManager.UNKNOWN_CARRIER_ID
import androidx.test.filters.SmallTest
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.TelephonyIcons.THREE_G
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel.MobileDisabled
import com.android.systemui.statusbar.pipeline.shared.data.model.toMobileDataActivityModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.DemoModeWifiDataSource
import com.android.systemui.statusbar.pipeline.wifi.data.repository.demo.model.FakeWifiEventModel
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class DemoMobileConnectionsRepositoryTest : SysuiTestCase() {
    private val dumpManager: DumpManager = mock()
    private val logFactory = TableLogBufferFactory(dumpManager, FakeSystemClock())

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeNetworkEventFlow = MutableStateFlow<FakeNetworkEventModel?>(null)
    private val fakeWifiEventFlow = MutableStateFlow<FakeWifiEventModel?>(null)

    private lateinit var underTest: DemoMobileConnectionsRepository
    private lateinit var mobileDataSource: DemoModeMobileConnectionDataSource
    private lateinit var wifiDataSource: DemoModeWifiDataSource

    @Before
    fun setUp() {
        // The data source only provides one API, so we can mock it with a flow here for convenience
        mobileDataSource =
            mock<DemoModeMobileConnectionDataSource>().also {
                whenever(it.mobileEvents).thenReturn(fakeNetworkEventFlow)
            }
        wifiDataSource =
            mock<DemoModeWifiDataSource>().also {
                whenever(it.wifiEvents).thenReturn(fakeWifiEventFlow)
            }

        underTest =
            DemoMobileConnectionsRepository(
                mobileDataSource = mobileDataSource,
                wifiDataSource = wifiDataSource,
                scope = testScope.backgroundScope,
                context = context,
                logFactory = logFactory,
            )

        underTest.startProcessingCommands()
    }

    @Test
    fun `connectivity - defaults to connected and validated`() =
        testScope.runTest {
            val connectivity = underTest.defaultMobileNetworkConnectivity.value
            assertThat(connectivity.isConnected).isTrue()
            assertThat(connectivity.isValidated).isTrue()
        }

    @Test
    fun `network event - create new subscription`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEmpty()

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(1)

            job.cancel()
        }

    @Test
    fun `wifi carrier merged event - create new subscription`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEmpty()

            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 5)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(5)

            job.cancel()
        }

    @Test
    fun `network event - reuses subscription when same Id`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEmpty()

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(1)

            // Second network event comes in with the same subId, does not create a new subscription
            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 2)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(1)

            job.cancel()
        }

    @Test
    fun `wifi carrier merged event - reuses subscription when same Id`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEmpty()

            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 5, level = 1)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(5)

            // Second network event comes in with the same subId, does not create a new subscription
            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 5, level = 2)

            assertThat(latest).hasSize(1)
            assertThat(latest!![0].subscriptionId).isEqualTo(5)

            job.cancel()
        }

    @Test
    fun `multiple subscriptions`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2)

            assertThat(latest).hasSize(2)

            job.cancel()
        }

    @Test
    fun `mobile subscription and carrier merged subscription`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)
            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 5)

            assertThat(latest).hasSize(2)

            job.cancel()
        }

    @Test
    fun `multiple mobile subscriptions and carrier merged subscription`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2)
            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 3)

            assertThat(latest).hasSize(3)

            job.cancel()
        }

    @Test
    fun `mobile disabled event - disables connection - subId specified - single conn`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = 1)

            assertThat(latest).hasSize(0)

            job.cancel()
        }

    @Test
    fun `mobile disabled event - disables connection - subId not specified - single conn`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = null)

            assertThat(latest).hasSize(0)

            job.cancel()
        }

    @Test
    fun `mobile disabled event - disables connection - subId specified - multiple conn`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = 2)

            assertThat(latest).hasSize(1)

            job.cancel()
        }

    @Test
    fun `mobile disabled event - subId not specified - multiple conn - ignores command`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = null)

            assertThat(latest).hasSize(2)

            job.cancel()
        }

    @Test
    fun `wifi network updates to disabled - carrier merged connection removed`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 1)

            assertThat(latest).hasSize(1)

            fakeWifiEventFlow.value = FakeWifiEventModel.WifiDisabled

            assertThat(latest).isEmpty()

            job.cancel()
        }

    @Test
    fun `wifi network updates to active - carrier merged connection removed`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 1)

            assertThat(latest).hasSize(1)

            fakeWifiEventFlow.value =
                FakeWifiEventModel.Wifi(
                    level = 1,
                    activity = 0,
                    ssid = null,
                    validated = true,
                )

            assertThat(latest).isEmpty()

            job.cancel()
        }

    @Test
    fun `mobile sub updates to carrier merged - only one connection`() =
        testScope.runTest {
            var latestSubsList: List<SubscriptionModel>? = null
            var connections: List<DemoMobileConnectionRepository>? = null
            val job =
                underTest.subscriptions
                    .onEach { latestSubsList = it }
                    .onEach { infos ->
                        connections =
                            infos.map { info -> underTest.getRepoForSubId(info.subscriptionId) }
                    }
                    .launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 3, level = 2)
            assertThat(latestSubsList).hasSize(1)

            val carrierMergedEvent = validCarrierMergedEvent(subId = 3, level = 1)
            fakeWifiEventFlow.value = carrierMergedEvent
            assertThat(latestSubsList).hasSize(1)
            val connection = connections!!.find { it.subId == 3 }!!
            assertCarrierMergedConnection(connection, carrierMergedEvent)

            job.cancel()
        }

    @Test
    fun `mobile sub updates to carrier merged then back - has old mobile data`() =
        testScope.runTest {
            var latestSubsList: List<SubscriptionModel>? = null
            var connections: List<DemoMobileConnectionRepository>? = null
            val job =
                underTest.subscriptions
                    .onEach { latestSubsList = it }
                    .onEach { infos ->
                        connections =
                            infos.map { info -> underTest.getRepoForSubId(info.subscriptionId) }
                    }
                    .launchIn(this)

            val mobileEvent = validMobileEvent(subId = 3, level = 2)
            fakeNetworkEventFlow.value = mobileEvent
            assertThat(latestSubsList).hasSize(1)

            val carrierMergedEvent = validCarrierMergedEvent(subId = 3, level = 1)
            fakeWifiEventFlow.value = carrierMergedEvent
            assertThat(latestSubsList).hasSize(1)
            var connection = connections!!.find { it.subId == 3 }!!
            assertCarrierMergedConnection(connection, carrierMergedEvent)

            // WHEN the carrier merged is removed
            fakeWifiEventFlow.value =
                FakeWifiEventModel.Wifi(
                    level = 4,
                    activity = 0,
                    ssid = null,
                    validated = true,
                )

            // THEN the subId=3 connection goes back to the mobile information
            connection = connections!!.find { it.subId == 3 }!!
            assertConnection(connection, mobileEvent)

            job.cancel()
        }

    /** Regression test for b/261706421 */
    @Test
    fun `multiple connections - remove all - does not throw`() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            // Two subscriptions are added
            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2, level = 1)

            // Then both are removed by turning off demo mode
            underTest.stopProcessingCommands()

            assertThat(latest).isEmpty()

            job.cancel()
        }

    @Test
    fun `demo connection - single subscription`() =
        testScope.runTest {
            var currentEvent: FakeNetworkEventModel = validMobileEvent(subId = 1)
            var connections: List<DemoMobileConnectionRepository>? = null
            val job =
                underTest.subscriptions
                    .onEach { infos ->
                        connections =
                            infos.map { info -> underTest.getRepoForSubId(info.subscriptionId) }
                    }
                    .launchIn(this)

            fakeNetworkEventFlow.value = currentEvent

            assertThat(connections).hasSize(1)
            val connection1 = connections!![0]

            assertConnection(connection1, currentEvent)

            // Exercise the whole api

            currentEvent = validMobileEvent(subId = 1, level = 2)
            fakeNetworkEventFlow.value = currentEvent
            assertConnection(connection1, currentEvent)

            job.cancel()
        }

    @Test
    fun `demo connection - two connections - update second - no affect on first`() =
        testScope.runTest {
            var currentEvent1 = validMobileEvent(subId = 1)
            var connection1: DemoMobileConnectionRepository? = null
            var currentEvent2 = validMobileEvent(subId = 2)
            var connection2: DemoMobileConnectionRepository? = null
            var connections: List<DemoMobileConnectionRepository>? = null
            val job =
                underTest.subscriptions
                    .onEach { infos ->
                        connections =
                            infos.map { info -> underTest.getRepoForSubId(info.subscriptionId) }
                    }
                    .launchIn(this)

            fakeNetworkEventFlow.value = currentEvent1
            fakeNetworkEventFlow.value = currentEvent2
            assertThat(connections).hasSize(2)
            connections!!.forEach {
                if (it.subId == 1) {
                    connection1 = it
                } else if (it.subId == 2) {
                    connection2 = it
                } else {
                    Assert.fail("Unexpected subscription")
                }
            }

            assertConnection(connection1!!, currentEvent1)
            assertConnection(connection2!!, currentEvent2)

            // WHEN the event changes for connection 2, it updates, and connection 1 stays the same
            currentEvent2 = validMobileEvent(subId = 2, activity = DATA_ACTIVITY_INOUT)
            fakeNetworkEventFlow.value = currentEvent2
            assertConnection(connection1!!, currentEvent1)
            assertConnection(connection2!!, currentEvent2)

            // and vice versa
            currentEvent1 = validMobileEvent(subId = 1, inflateStrength = true)
            fakeNetworkEventFlow.value = currentEvent1
            assertConnection(connection1!!, currentEvent1)
            assertConnection(connection2!!, currentEvent2)

            job.cancel()
        }

    @Test
    fun `demo connection - two connections - update carrier merged - no affect on first`() =
        testScope.runTest {
            var currentEvent1 = validMobileEvent(subId = 1)
            var connection1: DemoMobileConnectionRepository? = null
            var currentEvent2 = validCarrierMergedEvent(subId = 2)
            var connection2: DemoMobileConnectionRepository? = null
            var connections: List<DemoMobileConnectionRepository>? = null
            val job =
                underTest.subscriptions
                    .onEach { infos ->
                        connections =
                            infos.map { info -> underTest.getRepoForSubId(info.subscriptionId) }
                    }
                    .launchIn(this)

            fakeNetworkEventFlow.value = currentEvent1
            fakeWifiEventFlow.value = currentEvent2
            assertThat(connections).hasSize(2)
            connections!!.forEach {
                when (it.subId) {
                    1 -> connection1 = it
                    2 -> connection2 = it
                    else -> Assert.fail("Unexpected subscription")
                }
            }

            assertConnection(connection1!!, currentEvent1)
            assertCarrierMergedConnection(connection2!!, currentEvent2)

            // WHEN the event changes for connection 2, it updates, and connection 1 stays the same
            currentEvent2 = validCarrierMergedEvent(subId = 2, level = 4)
            fakeWifiEventFlow.value = currentEvent2
            assertConnection(connection1!!, currentEvent1)
            assertCarrierMergedConnection(connection2!!, currentEvent2)

            // and vice versa
            currentEvent1 = validMobileEvent(subId = 1, inflateStrength = true)
            fakeNetworkEventFlow.value = currentEvent1
            assertConnection(connection1!!, currentEvent1)
            assertCarrierMergedConnection(connection2!!, currentEvent2)

            job.cancel()
        }

    private fun assertConnection(
        conn: DemoMobileConnectionRepository,
        model: FakeNetworkEventModel
    ) {
        when (model) {
            is FakeNetworkEventModel.Mobile -> {
                val connectionInfo: MobileConnectionModel = conn.connectionInfo.value
                assertThat(conn.subId).isEqualTo(model.subId)
                assertThat(connectionInfo.cdmaLevel).isEqualTo(model.level)
                assertThat(connectionInfo.primaryLevel).isEqualTo(model.level)
                assertThat(connectionInfo.dataActivityDirection)
                    .isEqualTo((model.activity ?: DATA_ACTIVITY_NONE).toMobileDataActivityModel())
                assertThat(connectionInfo.carrierNetworkChangeActive)
                    .isEqualTo(model.carrierNetworkChange)
                assertThat(connectionInfo.isRoaming).isEqualTo(model.roaming)
                assertThat(conn.networkName.value).isEqualTo(NetworkNameModel.Derived(model.name))

                // TODO(b/261029387) check these once we start handling them
                assertThat(connectionInfo.isEmergencyOnly).isFalse()
                assertThat(connectionInfo.isGsm).isFalse()
                assertThat(connectionInfo.dataConnectionState)
                    .isEqualTo(DataConnectionState.Connected)
            }
            else -> {}
        }
    }

    private fun assertCarrierMergedConnection(
        conn: DemoMobileConnectionRepository,
        model: FakeWifiEventModel.CarrierMerged,
    ) {
        val connectionInfo: MobileConnectionModel = conn.connectionInfo.value
        assertThat(conn.subId).isEqualTo(model.subscriptionId)
        assertThat(connectionInfo.cdmaLevel).isEqualTo(model.level)
        assertThat(connectionInfo.primaryLevel).isEqualTo(model.level)
        assertThat(connectionInfo.carrierNetworkChangeActive).isEqualTo(false)
        assertThat(connectionInfo.isRoaming).isEqualTo(false)
        assertThat(connectionInfo.isEmergencyOnly).isFalse()
        assertThat(connectionInfo.isGsm).isFalse()
        assertThat(connectionInfo.dataConnectionState).isEqualTo(DataConnectionState.Connected)
    }
}

/** Convenience to create a valid fake network event with minimal params */
fun validMobileEvent(
    level: Int? = 1,
    dataType: SignalIcon.MobileIconGroup? = THREE_G,
    subId: Int? = 1,
    carrierId: Int? = UNKNOWN_CARRIER_ID,
    inflateStrength: Boolean? = false,
    activity: Int? = null,
    carrierNetworkChange: Boolean = false,
    roaming: Boolean = false,
): FakeNetworkEventModel =
    FakeNetworkEventModel.Mobile(
        level = level,
        dataType = dataType,
        subId = subId,
        carrierId = carrierId,
        inflateStrength = inflateStrength,
        activity = activity,
        carrierNetworkChange = carrierNetworkChange,
        roaming = roaming,
        name = "demo name",
    )

fun validCarrierMergedEvent(
    subId: Int = 1,
    level: Int = 1,
    numberOfLevels: Int = 4,
): FakeWifiEventModel.CarrierMerged =
    FakeWifiEventModel.CarrierMerged(
        subscriptionId = subId,
        level = level,
        numberOfLevels = numberOfLevels,
    )
