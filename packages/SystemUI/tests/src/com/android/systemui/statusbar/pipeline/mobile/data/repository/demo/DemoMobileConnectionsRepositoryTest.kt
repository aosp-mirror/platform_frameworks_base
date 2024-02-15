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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class DemoMobileConnectionsRepositoryTest : SysuiTestCase() {
    private val dumpManager: DumpManager = mock()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeNetworkEventFlow = MutableStateFlow<FakeNetworkEventModel?>(null)
    private val fakeWifiEventFlow = MutableStateFlow<FakeWifiEventModel?>(null)
    private val logFactory =
        TableLogBufferFactory(
            dumpManager,
            FakeSystemClock(),
            mock(),
            testDispatcher,
            testScope.backgroundScope,
        )

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
    fun isDefault_defaultsToTrue() =
        testScope.runTest {
            val isDefault = underTest.mobileIsDefault.value
            assertThat(isDefault).isTrue()
        }

    @Test
    fun validated_defaultsToTrue() =
        testScope.runTest {
            val isValidated = underTest.defaultConnectionIsValidated.value
            assertThat(isValidated).isTrue()
        }

    @Test
    fun networkEvent_createNewSubscription() =
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
    fun wifiCarrierMergedEvent_createNewSubscription() =
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
    fun networkEvent_reusesSubscriptionWhenSameId() =
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
    fun wifiCarrierMergedEvent_reusesSubscriptionWhenSameId() =
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
    fun multipleSubscriptions() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)
            fakeNetworkEventFlow.value = validMobileEvent(subId = 2)

            assertThat(latest).hasSize(2)

            job.cancel()
        }

    @Test
    fun mobileSubscriptionAndCarrierMergedSubscription() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1)
            fakeWifiEventFlow.value = validCarrierMergedEvent(subId = 5)

            assertThat(latest).hasSize(2)

            job.cancel()
        }

    @Test
    fun multipleMobileSubscriptionsAndCarrierMergedSubscription() =
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
    fun mobileDisabledEvent_disablesConnection_subIdSpecified_singleConn() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = 1)

            assertThat(latest).hasSize(0)

            job.cancel()
        }

    @Test
    fun mobileDisabledEvent_disablesConnection_subIdNotSpecified_singleConn() =
        testScope.runTest {
            var latest: List<SubscriptionModel>? = null
            val job = underTest.subscriptions.onEach { latest = it }.launchIn(this)

            fakeNetworkEventFlow.value = validMobileEvent(subId = 1, level = 1)

            fakeNetworkEventFlow.value = MobileDisabled(subId = null)

            assertThat(latest).hasSize(0)

            job.cancel()
        }

    @Test
    fun mobileDisabledEvent_disablesConnection_subIdSpecified_multipleConn() =
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
    fun mobileDisabledEvent_subIdNotSpecified_multipleConn_ignoresCommand() =
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
    fun wifiNetworkUpdatesToDisabled_carrierMergedConnectionRemoved() =
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
    fun wifiNetworkUpdatesToActive_carrierMergedConnectionRemoved() =
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
    fun mobileSubUpdatesToCarrierMerged_onlyOneConnection() =
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
    fun mobileSubUpdatesToCarrierMergedThenBack_hasOldMobileData() =
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
    fun multipleConnections_removeAll_doesNotThrow() =
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
    fun demoConnection_singleSubscription() =
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
    fun demoConnection_twoConnections_updateSecond_noAffectOnFirst() =
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
    fun demoConnection_twoConnections_updateCarrierMerged_noAffectOnFirst() =
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

    @Test
    fun demoIsNotInEcmState() = testScope.runTest { assertThat(underTest.isInEcmMode()).isFalse() }

    private fun TestScope.startCollection(conn: DemoMobileConnectionRepository): Job {
        val job = launch {
            launch { conn.cdmaLevel.collect {} }
            launch { conn.primaryLevel.collect {} }
            launch { conn.dataActivityDirection.collect {} }
            launch { conn.carrierNetworkChangeActive.collect {} }
            launch { conn.isRoaming.collect {} }
            launch { conn.networkName.collect {} }
            launch { conn.carrierName.collect {} }
            launch { conn.isEmergencyOnly.collect {} }
            launch { conn.dataConnectionState.collect {} }
            launch { conn.hasPrioritizedNetworkCapabilities.collect {} }
        }
        return job
    }

    private fun TestScope.assertConnection(
        conn: DemoMobileConnectionRepository,
        model: FakeNetworkEventModel,
    ) {
        val job = startCollection(conn)
        // Assert the fields using the `MutableStateFlow` so that we don't have to start up
        // a collector for every field for every test
        when (model) {
            is FakeNetworkEventModel.Mobile -> {
                assertThat(conn.subId).isEqualTo(model.subId)
                assertThat(conn.cdmaLevel.value).isEqualTo(model.level)
                assertThat(conn.primaryLevel.value).isEqualTo(model.level)
                assertThat(conn.dataActivityDirection.value)
                    .isEqualTo((model.activity ?: DATA_ACTIVITY_NONE).toMobileDataActivityModel())
                assertThat(conn.carrierNetworkChangeActive.value)
                    .isEqualTo(model.carrierNetworkChange)
                assertThat(conn.isRoaming.value).isEqualTo(model.roaming)
                assertThat(conn.networkName.value)
                    .isEqualTo(NetworkNameModel.IntentDerived(model.name))
                assertThat(conn.carrierName.value)
                    .isEqualTo(NetworkNameModel.SubscriptionDerived("${model.name} ${model.subId}"))
                assertThat(conn.hasPrioritizedNetworkCapabilities.value).isEqualTo(model.slice)
                assertThat(conn.isNonTerrestrial.value).isEqualTo(model.ntn)

                // TODO(b/261029387) check these once we start handling them
                assertThat(conn.isEmergencyOnly.value).isFalse()
                assertThat(conn.isGsm.value).isFalse()
                assertThat(conn.dataConnectionState.value).isEqualTo(DataConnectionState.Connected)
            }
            else -> {}
        }

        job.cancel()
    }

    private fun TestScope.assertCarrierMergedConnection(
        conn: DemoMobileConnectionRepository,
        model: FakeWifiEventModel.CarrierMerged,
    ) {
        val job = startCollection(conn)
        assertThat(conn.subId).isEqualTo(model.subscriptionId)
        assertThat(conn.cdmaLevel.value).isEqualTo(model.level)
        assertThat(conn.primaryLevel.value).isEqualTo(model.level)
        assertThat(conn.carrierNetworkChangeActive.value).isEqualTo(false)
        assertThat(conn.isRoaming.value).isEqualTo(false)
        assertThat(conn.isEmergencyOnly.value).isFalse()
        assertThat(conn.isGsm.value).isFalse()
        assertThat(conn.dataConnectionState.value).isEqualTo(DataConnectionState.Connected)
        assertThat(conn.hasPrioritizedNetworkCapabilities.value).isFalse()
        job.cancel()
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
    activity: Int = DATA_ACTIVITY_NONE,
): FakeWifiEventModel.CarrierMerged =
    FakeWifiEventModel.CarrierMerged(
        subscriptionId = subId,
        level = level,
        numberOfLevels = numberOfLevels,
        activity = activity,
    )
