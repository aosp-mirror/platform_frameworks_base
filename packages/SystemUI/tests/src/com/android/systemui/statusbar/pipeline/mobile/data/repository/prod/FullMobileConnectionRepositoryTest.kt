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

import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_EMERGENCY
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_OPERATOR
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel.Companion.COL_PRIMARY_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.getTelephonyCallbackForType
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/**
 * This repo acts as a dispatcher to either the `typical` or `carrier merged` versions of the
 * repository interface it's switching on. These tests just need to verify that the entire interface
 * properly switches over when the value of `isCarrierMerged` changes.
 */
@Suppress("EXPERIMENTAL_IS_NOT_ENABLED")
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class FullMobileConnectionRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: FullMobileConnectionRepository

    private val systemClock = FakeSystemClock()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val tableLogBuffer = TableLogBuffer(maxSize = 100, name = "TestName", systemClock)
    private val mobileFactory = mock<MobileConnectionRepositoryImpl.Factory>()
    private val carrierMergedFactory = mock<CarrierMergedConnectionRepository.Factory>()

    private lateinit var mobileRepo: FakeMobileConnectionRepository
    private lateinit var carrierMergedRepo: FakeMobileConnectionRepository

    @Before
    fun setUp() {
        mobileRepo = FakeMobileConnectionRepository(SUB_ID, tableLogBuffer)
        carrierMergedRepo = FakeMobileConnectionRepository(SUB_ID, tableLogBuffer)

        whenever(
                mobileFactory.build(
                    eq(SUB_ID),
                    any(),
                    eq(DEFAULT_NAME),
                    eq(SEP),
                )
            )
            .thenReturn(mobileRepo)
        whenever(carrierMergedFactory.build(eq(SUB_ID), any(), eq(DEFAULT_NAME)))
            .thenReturn(carrierMergedRepo)
    }

    @Test
    fun startingIsCarrierMerged_usesCarrierMergedInitially() =
        testScope.runTest {
            val carrierMergedConnectionInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "Carrier Merged Operator",
                )
            carrierMergedRepo.setConnectionInfo(carrierMergedConnectionInfo)

            initializeRepo(startingIsCarrierMerged = true)

            assertThat(underTest.activeRepo.value).isEqualTo(carrierMergedRepo)
            assertThat(underTest.connectionInfo.value).isEqualTo(carrierMergedConnectionInfo)
            verify(mobileFactory, never())
                .build(
                    SUB_ID,
                    tableLogBuffer,
                    DEFAULT_NAME,
                    SEP,
                )
        }

    @Test
    fun startingNotCarrierMerged_usesTypicalInitially() =
        testScope.runTest {
            val mobileConnectionInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "Typical Operator",
                )
            mobileRepo.setConnectionInfo(mobileConnectionInfo)

            initializeRepo(startingIsCarrierMerged = false)

            assertThat(underTest.activeRepo.value).isEqualTo(mobileRepo)
            assertThat(underTest.connectionInfo.value).isEqualTo(mobileConnectionInfo)
            verify(carrierMergedFactory, never()).build(SUB_ID, tableLogBuffer, DEFAULT_NAME)
        }

    @Test
    fun activeRepo_matchesIsCarrierMerged() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)
            var latest: MobileConnectionRepository? = null
            val job = underTest.activeRepo.onEach { latest = it }.launchIn(this)

            underTest.setIsCarrierMerged(true)

            assertThat(latest).isEqualTo(carrierMergedRepo)

            underTest.setIsCarrierMerged(false)

            assertThat(latest).isEqualTo(mobileRepo)

            underTest.setIsCarrierMerged(true)

            assertThat(latest).isEqualTo(carrierMergedRepo)

            job.cancel()
        }

    @Test
    fun connectionInfo_getsUpdatesFromRepo_carrierMerged() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            underTest.setIsCarrierMerged(true)

            val info1 =
                MobileConnectionModel(
                    operatorAlphaShort = "Carrier Merged Operator",
                    primaryLevel = 1,
                )
            carrierMergedRepo.setConnectionInfo(info1)

            assertThat(latest).isEqualTo(info1)

            val info2 =
                MobileConnectionModel(
                    operatorAlphaShort = "Carrier Merged Operator #2",
                    primaryLevel = 2,
                )
            carrierMergedRepo.setConnectionInfo(info2)

            assertThat(latest).isEqualTo(info2)

            val info3 =
                MobileConnectionModel(
                    operatorAlphaShort = "Carrier Merged Operator #3",
                    primaryLevel = 3,
                )
            carrierMergedRepo.setConnectionInfo(info3)

            assertThat(latest).isEqualTo(info3)

            job.cancel()
        }

    @Test
    fun connectionInfo_getsUpdatesFromRepo_mobile() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            underTest.setIsCarrierMerged(false)

            val info1 =
                MobileConnectionModel(
                    operatorAlphaShort = "Typical Merged Operator",
                    primaryLevel = 1,
                )
            mobileRepo.setConnectionInfo(info1)

            assertThat(latest).isEqualTo(info1)

            val info2 =
                MobileConnectionModel(
                    operatorAlphaShort = "Typical Merged Operator #2",
                    primaryLevel = 2,
                )
            mobileRepo.setConnectionInfo(info2)

            assertThat(latest).isEqualTo(info2)

            val info3 =
                MobileConnectionModel(
                    operatorAlphaShort = "Typical Merged Operator #3",
                    primaryLevel = 3,
                )
            mobileRepo.setConnectionInfo(info3)

            assertThat(latest).isEqualTo(info3)

            job.cancel()
        }

    @Test
    fun connectionInfo_updatesWhenCarrierMergedUpdates() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            var latest: MobileConnectionModel? = null
            val job = underTest.connectionInfo.onEach { latest = it }.launchIn(this)

            val carrierMergedInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "Carrier Merged Operator",
                    primaryLevel = 4,
                )
            carrierMergedRepo.setConnectionInfo(carrierMergedInfo)

            val mobileInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "Typical Operator",
                    primaryLevel = 2,
                )
            mobileRepo.setConnectionInfo(mobileInfo)

            // Start with the mobile info
            assertThat(latest).isEqualTo(mobileInfo)

            // WHEN isCarrierMerged is set to true
            underTest.setIsCarrierMerged(true)

            // THEN the carrier merged info is used
            assertThat(latest).isEqualTo(carrierMergedInfo)

            val newCarrierMergedInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "New CM Operator",
                    primaryLevel = 0,
                )
            carrierMergedRepo.setConnectionInfo(newCarrierMergedInfo)

            assertThat(latest).isEqualTo(newCarrierMergedInfo)

            // WHEN isCarrierMerged is set to false
            underTest.setIsCarrierMerged(false)

            // THEN the typical info is used
            assertThat(latest).isEqualTo(mobileInfo)

            val newMobileInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "New Mobile Operator",
                    primaryLevel = 3,
                )
            mobileRepo.setConnectionInfo(newMobileInfo)

            assertThat(latest).isEqualTo(newMobileInfo)

            job.cancel()
        }

    @Test
    fun `factory - reuses log buffers for same connection`() =
        testScope.runTest {
            val realLoggerFactory = TableLogBufferFactory(mock(), FakeSystemClock())

            val factory =
                FullMobileConnectionRepository.Factory(
                    scope = testScope.backgroundScope,
                    realLoggerFactory,
                    mobileFactory,
                    carrierMergedFactory,
                )

            // Create two connections for the same subId. Similar to if the connection appeared
            // and disappeared from the connectionFactory's perspective
            val connection1 =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = false,
                    DEFAULT_NAME,
                    SEP,
                )

            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = false,
                    DEFAULT_NAME,
                    SEP,
                )

            assertThat(connection1.tableLogBuffer)
                .isSameInstanceAs(connection1Repeat.tableLogBuffer)
        }

    @Test
    fun `factory - reuses log buffers for same sub ID even if carrier merged`() =
        testScope.runTest {
            val realLoggerFactory = TableLogBufferFactory(mock(), FakeSystemClock())

            val factory =
                FullMobileConnectionRepository.Factory(
                    scope = testScope.backgroundScope,
                    realLoggerFactory,
                    mobileFactory,
                    carrierMergedFactory,
                )

            val connection1 =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = false,
                    DEFAULT_NAME,
                    SEP,
                )

            // WHEN a connection with the same sub ID but carrierMerged = true is created
            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = true,
                    DEFAULT_NAME,
                    SEP,
                )

            // THEN the same table is re-used
            assertThat(connection1.tableLogBuffer)
                .isSameInstanceAs(connection1Repeat.tableLogBuffer)
        }

    @Test
    fun connectionInfo_logging_notCarrierMerged_getsUpdates() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            val telephonyManager = mock<TelephonyManager>()
            createRealMobileRepo(telephonyManager)
            createRealCarrierMergedRepo(FakeWifiRepository())

            initializeRepo(startingIsCarrierMerged = false)

            val job = underTest.connectionInfo.launchIn(this)

            // WHEN we set up some mobile connection info
            val serviceState = ServiceState()
            serviceState.setOperatorName("longName", "OpTypical", "1")
            serviceState.isEmergencyOnly = false
            getTelephonyCallbackForType<TelephonyCallback.ServiceStateListener>(telephonyManager)
                .onServiceStateChanged(serviceState)

            // THEN it's logged to the buffer
            assertThat(dumpBuffer()).contains("$COL_OPERATOR${BUFFER_SEPARATOR}OpTypical")
            assertThat(dumpBuffer()).contains("$COL_EMERGENCY${BUFFER_SEPARATOR}false")

            // WHEN we update mobile connection info
            val serviceState2 = ServiceState()
            serviceState2.setOperatorName("longName", "OpDiff", "1")
            serviceState2.isEmergencyOnly = true
            getTelephonyCallbackForType<TelephonyCallback.ServiceStateListener>(telephonyManager)
                .onServiceStateChanged(serviceState2)

            // THEN the updates are logged
            assertThat(dumpBuffer()).contains("$COL_OPERATOR${BUFFER_SEPARATOR}OpDiff")
            assertThat(dumpBuffer()).contains("$COL_EMERGENCY${BUFFER_SEPARATOR}true")

            job.cancel()
        }

    @Test
    fun connectionInfo_logging_carrierMerged_getsUpdates() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            createRealMobileRepo(mock())
            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(wifiRepository)

            initializeRepo(startingIsCarrierMerged = true)

            val job = underTest.connectionInfo.launchIn(this)

            // WHEN we set up carrier merged info
            val networkId = 2
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 3,
                )
            )

            // THEN the carrier merged info is logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}3")

            // WHEN we update the info
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 1,
                )
            )

            // THEN the updates are logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}1")

            job.cancel()
        }

    @Test
    fun connectionInfo_logging_updatesWhenCarrierMergedUpdates() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            val telephonyManager = mock<TelephonyManager>()
            createRealMobileRepo(telephonyManager)

            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(wifiRepository)

            initializeRepo(startingIsCarrierMerged = false)

            val job = underTest.connectionInfo.launchIn(this)

            // WHEN we set up some mobile connection info
            val signalStrength = mock<SignalStrength>()
            whenever(signalStrength.level).thenReturn(1)

            getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>(telephonyManager)
                .onSignalStrengthsChanged(signalStrength)

            // THEN it's logged to the buffer
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}1")

            // WHEN isCarrierMerged is set to true
            val networkId = 2
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 3,
                )
            )
            underTest.setIsCarrierMerged(true)

            // THEN the carrier merged info is logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}3")

            // WHEN the carrier merge network is updated
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 4,
                )
            )

            // THEN the new level is logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}4")

            // WHEN isCarrierMerged is set to false
            underTest.setIsCarrierMerged(false)

            // THEN the typical info is logged
            // Note: Since our first logs also had the typical info, we need to search the log
            // contents for after our carrier merged level log.
            val fullBuffer = dumpBuffer()
            val carrierMergedContentIndex = fullBuffer.indexOf("${BUFFER_SEPARATOR}4")
            val bufferAfterCarrierMerged = fullBuffer.substring(carrierMergedContentIndex)
            assertThat(bufferAfterCarrierMerged).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}1")

            // WHEN the normal network is updated
            val newMobileInfo =
                MobileConnectionModel(
                    operatorAlphaShort = "Mobile Operator 2",
                    primaryLevel = 0,
                )
            mobileRepo.setConnectionInfo(newMobileInfo)

            // THEN the new level is logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}0")

            job.cancel()
        }

    @Test
    fun connectionInfo_logging_doesNotLogUpdatesForNotActiveRepo() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            val telephonyManager = mock<TelephonyManager>()
            createRealMobileRepo(telephonyManager)

            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(wifiRepository)

            // WHEN isCarrierMerged = false
            initializeRepo(startingIsCarrierMerged = false)

            val job = underTest.connectionInfo.launchIn(this)

            val signalStrength = mock<SignalStrength>()
            whenever(signalStrength.level).thenReturn(1)
            getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>(telephonyManager)
                .onSignalStrengthsChanged(signalStrength)

            // THEN updates to the carrier merged level aren't logged
            val networkId = 2
            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 4,
                )
            )
            assertThat(dumpBuffer()).doesNotContain("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}4")

            wifiRepository.setWifiNetwork(
                WifiNetworkModel.CarrierMerged(
                    networkId,
                    SUB_ID,
                    level = 3,
                )
            )
            assertThat(dumpBuffer()).doesNotContain("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}3")

            // WHEN isCarrierMerged is set to true
            underTest.setIsCarrierMerged(true)

            // THEN updates to the normal level aren't logged
            whenever(signalStrength.level).thenReturn(5)
            getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>(telephonyManager)
                .onSignalStrengthsChanged(signalStrength)
            assertThat(dumpBuffer()).doesNotContain("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}5")

            whenever(signalStrength.level).thenReturn(6)
            getTelephonyCallbackForType<TelephonyCallback.SignalStrengthsListener>(telephonyManager)
                .onSignalStrengthsChanged(signalStrength)
            assertThat(dumpBuffer()).doesNotContain("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}6")

            job.cancel()
        }

    private fun initializeRepo(startingIsCarrierMerged: Boolean) {
        underTest =
            FullMobileConnectionRepository(
                SUB_ID,
                startingIsCarrierMerged,
                tableLogBuffer,
                DEFAULT_NAME,
                SEP,
                testScope.backgroundScope,
                mobileFactory,
                carrierMergedFactory,
            )
    }

    private fun createRealMobileRepo(
        telephonyManager: TelephonyManager,
    ): MobileConnectionRepositoryImpl {
        whenever(telephonyManager.subscriptionId).thenReturn(SUB_ID)

        val realRepo =
            MobileConnectionRepositoryImpl(
                context,
                SUB_ID,
                defaultNetworkName = NetworkNameModel.Default("default"),
                networkNameSeparator = SEP,
                telephonyManager,
                systemUiCarrierConfig = mock(),
                fakeBroadcastDispatcher,
                mobileMappingsProxy = mock(),
                testDispatcher,
                logger = mock(),
                tableLogBuffer,
                testScope.backgroundScope,
            )
        whenever(
                mobileFactory.build(
                    eq(SUB_ID),
                    any(),
                    eq(DEFAULT_NAME),
                    eq(SEP),
                )
            )
            .thenReturn(realRepo)

        return realRepo
    }

    private fun createRealCarrierMergedRepo(
        wifiRepository: FakeWifiRepository,
    ): CarrierMergedConnectionRepository {
        wifiRepository.setIsWifiEnabled(true)
        wifiRepository.setIsWifiDefault(true)
        val realRepo =
            CarrierMergedConnectionRepository(
                SUB_ID,
                tableLogBuffer,
                defaultNetworkName = NetworkNameModel.Default("default"),
                testScope.backgroundScope,
                wifiRepository,
            )
        whenever(carrierMergedFactory.build(eq(SUB_ID), any(), eq(DEFAULT_NAME)))
            .thenReturn(realRepo)

        return realRepo
    }

    private fun dumpBuffer(): String {
        val outputWriter = StringWriter()
        tableLogBuffer.dump(PrintWriter(outputWriter), arrayOf())
        return outputWriter.toString()
    }

    private companion object {
        const val SUB_ID = 42
        private val DEFAULT_NAME = NetworkNameModel.Default("default name")
        private const val SEP = "-"
        private const val BUFFER_SEPARATOR = "|"
    }
}
