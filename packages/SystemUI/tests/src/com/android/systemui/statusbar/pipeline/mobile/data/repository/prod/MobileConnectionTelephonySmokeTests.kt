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

package com.android.systemui.statusbar.pipeline.mobile.data.repository.prod

import android.net.ConnectivityManager
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyCallback.CarrierNetworkListener
import android.telephony.TelephonyCallback.DataActivityListener
import android.telephony.TelephonyCallback.DataConnectionStateListener
import android.telephony.TelephonyCallback.DataEnabledListener
import android.telephony.TelephonyCallback.DisplayInfoListener
import android.telephony.TelephonyCallback.ServiceStateListener
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.DATA_ACTIVITY_INOUT
import android.telephony.TelephonyManager.NETWORK_TYPE_LTE
import android.telephony.TelephonyManager.NETWORK_TYPE_UNKNOWN
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.statusbar.pipeline.mobile.data.MobileInputLogger
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.ResolvedNetworkType
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfigTest
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.getTelephonyCallbackForType
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.signalStrength
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.model.DataActivityModel
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations

/**
 * Test class to stress test the TelephonyCallbacks that we listen to. In particular, the callbacks
 * all come back in on a single listener (for reasons defined in the system). This test is built to
 * ensure that we don't miss any important callbacks.
 *
 * Kind of like an interaction test case build just for [TelephonyCallback]
 *
 * The list of telephony callbacks we use is:
 * - [TelephonyCallback.CarrierNetworkListener]
 * - [TelephonyCallback.DataActivityListener]
 * - [TelephonyCallback.DataConnectionStateListener]
 * - [TelephonyCallback.DataEnabledListener]
 * - [TelephonyCallback.DisplayInfoListener]
 * - [TelephonyCallback.ServiceStateListener]
 * - [TelephonyCallback.SignalStrengthsListener]
 *
 * Because each of these callbacks comes in on the same callbackFlow, collecting on a field backed
 * by only a single callback can immediately create backpressure on the other fields related to a
 * mobile connection.
 *
 * This test should be designed to test _at least_ each individual callback in a smoke-test fashion.
 * The way we will achieve this is as follows:
 * 1. Start up a listener (A) collecting on a field which is _not under test_
 * 2. Send a single event to a telephony callback which supports the field under test (B)
 * 3. Send many (may be as few as 2) events to the callback backing A to ensure we start seeing
 *    backpressure on other fields NOTE: poor handling of backpressure here would normally cause B
 *    to get dropped
 * 4. Start up a new collector for B
 * 5. Assert that B has the state sent in step #2
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class MobileConnectionTelephonySmokeTests : SysuiTestCase() {
    private lateinit var underTest: MobileConnectionRepositoryImpl

    private val flags =
        FakeFeatureFlagsClassic().also { it.set(Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO, true) }

    @Mock private lateinit var connectivityManager: ConnectivityManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var logger: MobileInputLogger
    @Mock private lateinit var tableLogger: TableLogBuffer
    @Mock private lateinit var subscriptionModel: StateFlow<SubscriptionModel?>

    private val mobileMappings = FakeMobileMappingsProxy()
    private val systemUiCarrierConfig =
        SystemUiCarrierConfig(
            SUB_1_ID,
            SystemUiCarrierConfigTest.createTestConfig(),
        )

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(telephonyManager.subscriptionId).thenReturn(SUB_1_ID)

        underTest =
            MobileConnectionRepositoryImpl(
                SUB_1_ID,
                context,
                subscriptionModel,
                DEFAULT_NAME,
                SEP,
                connectivityManager,
                telephonyManager,
                systemUiCarrierConfig,
                fakeBroadcastDispatcher,
                mobileMappings,
                testDispatcher,
                logger,
                tableLogger,
                flags,
                testScope.backgroundScope,
            )
    }

    @Test
    fun carrierNetworkChangeListener_noisyActivity() =
        testScope.runTest {
            var latest: Boolean? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            val callback = getTelephonyCallbackForType<CarrierNetworkListener>()
            callback.onCarrierNetworkChange(true)

            flipActivity(100, activityCallback)

            val job = underTest.carrierNetworkChangeActive.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            activityJob.cancel()
            job.cancel()
        }

    @Test
    fun dataActivityLate_noisyDisplayInfo() =
        testScope.runTest {
            var latest: DataActivityModel? = null

            // start collecting displayInfo; don't care about the result
            val displayInfoJob = underTest.resolvedNetworkType.launchIn(this)

            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()
            activityCallback.onDataActivity(DATA_ACTIVITY_INOUT)

            val displayInfoCallback = getTelephonyCallbackForType<DisplayInfoListener>()
            val type1 = NETWORK_TYPE_UNKNOWN
            val type2 = NETWORK_TYPE_LTE
            val t1 =
                mock<TelephonyDisplayInfo>().also { whenever(it.networkType).thenReturn(type1) }
            val t2 =
                mock<TelephonyDisplayInfo>().also { whenever(it.networkType).thenReturn(type2) }

            flipDisplayInfo(100, listOf(t1, t2), displayInfoCallback)

            val job = underTest.dataActivityDirection.onEach { latest = it }.launchIn(this)

            assertThat(latest)
                .isEqualTo(
                    DataActivityModel(
                        hasActivityIn = true,
                        hasActivityOut = true,
                    )
                )

            displayInfoJob.cancel()
            job.cancel()
        }

    @Test
    fun dataConnectionStateListener_noisyActivity() =
        testScope.runTest {
            var latest: DataConnectionState? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)

            val connectionCallback = getTelephonyCallbackForType<DataConnectionStateListener>()
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            connectionCallback.onDataConnectionStateChanged(
                TelephonyManager.DATA_CONNECTED,
                200 /* unused */
            )

            flipActivity(100, activityCallback)

            val connectionJob = underTest.dataConnectionState.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(DataConnectionState.Connected)

            activityJob.cancel()
            connectionJob.cancel()
        }

    @Test
    fun dataEnabledLate_noisyActivity() =
        testScope.runTest {
            var latest: Boolean? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)

            val enabledCallback = getTelephonyCallbackForType<DataEnabledListener>()
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            enabledCallback.onDataEnabledChanged(true, 1 /* unused */)

            flipActivity(100, activityCallback)

            val job = underTest.dataEnabled.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            activityJob.cancel()
            job.cancel()
        }

    @Test
    fun displayInfoLate_noisyActivity() =
        testScope.runTest {
            var latest: ResolvedNetworkType? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)

            val displayInfoCallback = getTelephonyCallbackForType<DisplayInfoListener>()
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            val type = NETWORK_TYPE_LTE
            val expected = ResolvedNetworkType.DefaultNetworkType(mobileMappings.toIconKey(type))
            val ti = mock<TelephonyDisplayInfo>().also { whenever(it.networkType).thenReturn(type) }
            displayInfoCallback.onDisplayInfoChanged(ti)

            flipActivity(100, activityCallback)

            val job = underTest.resolvedNetworkType.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(expected)

            activityJob.cancel()
            job.cancel()
        }

    @Test
    fun serviceStateListener_noisyActivity() =
        testScope.runTest {
            var latest: Boolean? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)

            val serviceStateCallback = getTelephonyCallbackForType<ServiceStateListener>()
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            // isEmergencyOnly comes in
            val serviceState = ServiceState()
            serviceState.isEmergencyOnly = true
            serviceStateCallback.onServiceStateChanged(serviceState)

            flipActivity(100, activityCallback)

            val job = underTest.isEmergencyOnly.onEach { latest = it }.launchIn(this)

            assertThat(latest).isTrue()

            activityJob.cancel()
            job.cancel()
        }

    @Test
    fun signalStrengthsListenerLate_noisyActivity() =
        testScope.runTest {
            var latest: Int? = null

            // Start collecting data activity; don't care about the result
            val activityJob = underTest.dataActivityDirection.launchIn(this)
            val activityCallback = getTelephonyCallbackForType<DataActivityListener>()

            val callback = getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>()
            val strength = signalStrength(gsmLevel = 1, cdmaLevel = 2, isGsm = true)
            callback.onSignalStrengthsChanged(strength)

            flipActivity(100, activityCallback)

            val job = underTest.cdmaLevel.onEach { latest = it }.launchIn(this)

            assertThat(latest).isEqualTo(2)

            activityJob.cancel()
            job.cancel()
        }

    private fun flipActivity(
        times: Int,
        callback: DataActivityListener,
    ) {
        repeat(times) { index -> callback.onDataActivity(index % 4) }
    }

    private fun flipDisplayInfo(
        times: Int,
        infos: List<TelephonyDisplayInfo>,
        callback: DisplayInfoListener,
    ) {
        val len = infos.size
        repeat(times) { index -> callback.onDisplayInfoChanged(infos[index % len]) }
    }

    private inline fun <reified T> getTelephonyCallbackForType(): T {
        return getTelephonyCallbackForType(telephonyManager)
    }

    companion object {
        private const val SUB_1_ID = 1

        private val DEFAULT_NAME = NetworkNameModel.Default("default name")
        private const val SEP = "-"
    }
}
