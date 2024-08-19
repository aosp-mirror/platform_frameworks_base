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
import android.os.PersistableBundle
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags.ROAMING_INDICATOR_VIA_DISPLAY_INFO
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.SystemUiCarrierConfig
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_EMERGENCY
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_OPERATOR
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.FullMobileConnectionRepository.Companion.COL_PRIMARY_LEVEL
import com.android.systemui.statusbar.pipeline.mobile.data.repository.prod.MobileTelephonyHelpers.getTelephonyCallbackForType
import com.android.systemui.statusbar.pipeline.wifi.data.repository.FakeWifiRepository
import com.android.systemui.statusbar.pipeline.wifi.shared.model.WifiNetworkModel
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
@RunWith(AndroidJUnit4::class)
class FullMobileConnectionRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: FullMobileConnectionRepository

    private val flags =
        FakeFeatureFlagsClassic().also { it.set(ROAMING_INDICATOR_VIA_DISPLAY_INFO, true) }

    private val systemClock = FakeSystemClock()
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val tableLogBuffer =
        TableLogBuffer(
            maxSize = 100,
            name = "TestName",
            systemClock,
            mock(),
            testDispatcher,
            testScope.backgroundScope,
        )
    private val mobileFactory = mock<MobileConnectionRepositoryImpl.Factory>()
    private val carrierMergedFactory = mock<CarrierMergedConnectionRepository.Factory>()
    private val connectivityManager = mock<ConnectivityManager>()

    private val subscriptionModel =
        MutableStateFlow(
            SubscriptionModel(
                subscriptionId = SUB_ID,
                carrierName = DEFAULT_NAME,
                profileClass = PROFILE_CLASS_UNSET,
            )
        )

    // Use a real config, with no overrides
    private val systemUiCarrierConfig = SystemUiCarrierConfig(SUB_ID, PersistableBundle())

    private lateinit var mobileRepo: FakeMobileConnectionRepository
    private lateinit var carrierMergedRepo: FakeMobileConnectionRepository

    @Before
    fun setUp() {
        mobileRepo =
            FakeMobileConnectionRepository(
                SUB_ID,
                tableLogBuffer,
            )
        carrierMergedRepo =
            FakeMobileConnectionRepository(
                    SUB_ID,
                    tableLogBuffer,
                )
                .apply {
                    // Mimicks the real carrier merged repository
                    this.isAllowedDuringAirplaneMode.value = true
                }

        whenever(
                mobileFactory.build(
                    eq(SUB_ID),
                    any(),
                    any(),
                    eq(DEFAULT_NAME_MODEL),
                    eq(SEP),
                )
            )
            .thenReturn(mobileRepo)
        whenever(
                carrierMergedFactory.build(
                    eq(SUB_ID),
                    any(),
                )
            )
            .thenReturn(carrierMergedRepo)
    }

    @Test
    fun startingIsCarrierMerged_usesCarrierMergedInitially() =
        testScope.runTest {
            val carrierMergedOperatorName = "Carrier Merged Operator"
            val nonCarrierMergedName = "Non-carrier-merged"

            carrierMergedRepo.operatorAlphaShort.value = carrierMergedOperatorName
            mobileRepo.operatorAlphaShort.value = nonCarrierMergedName

            initializeRepo(startingIsCarrierMerged = true)

            assertThat(underTest.activeRepo.value).isEqualTo(carrierMergedRepo)
            assertThat(underTest.operatorAlphaShort.value).isEqualTo(carrierMergedOperatorName)
            verify(mobileFactory, never())
                .build(
                    SUB_ID,
                    tableLogBuffer,
                    subscriptionModel,
                    DEFAULT_NAME_MODEL,
                    SEP,
                )
        }

    @Test
    fun startingNotCarrierMerged_usesTypicalInitially() =
        testScope.runTest {
            val carrierMergedOperatorName = "Carrier Merged Operator"
            val nonCarrierMergedName = "Typical Operator"

            carrierMergedRepo.operatorAlphaShort.value = carrierMergedOperatorName
            mobileRepo.operatorAlphaShort.value = nonCarrierMergedName

            initializeRepo(startingIsCarrierMerged = false)

            assertThat(underTest.activeRepo.value).isEqualTo(mobileRepo)
            assertThat(underTest.operatorAlphaShort.value).isEqualTo(nonCarrierMergedName)
            verify(carrierMergedFactory, never())
                .build(
                    SUB_ID,
                    tableLogBuffer,
                )
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

            var latestName: String? = null
            var latestLevel: Int? = null

            val nameJob = underTest.operatorAlphaShort.onEach { latestName = it }.launchIn(this)
            val levelJob = underTest.primaryLevel.onEach { latestLevel = it }.launchIn(this)

            underTest.setIsCarrierMerged(true)

            val operator1 = "Carrier Merged Operator"
            val level1 = 1
            carrierMergedRepo.operatorAlphaShort.value = operator1
            carrierMergedRepo.primaryLevel.value = level1

            assertThat(latestName).isEqualTo(operator1)
            assertThat(latestLevel).isEqualTo(level1)

            val operator2 = "Carrier Merged Operator #2"
            val level2 = 2
            carrierMergedRepo.operatorAlphaShort.value = operator2
            carrierMergedRepo.primaryLevel.value = level2

            assertThat(latestName).isEqualTo(operator2)
            assertThat(latestLevel).isEqualTo(level2)

            val operator3 = "Carrier Merged Operator #3"
            val level3 = 3
            carrierMergedRepo.operatorAlphaShort.value = operator3
            carrierMergedRepo.primaryLevel.value = level3

            assertThat(latestName).isEqualTo(operator3)
            assertThat(latestLevel).isEqualTo(level3)

            nameJob.cancel()
            levelJob.cancel()
        }

    @Test
    fun connectionInfo_getsUpdatesFromRepo_mobile() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            var latestName: String? = null
            var latestLevel: Int? = null

            val nameJob = underTest.operatorAlphaShort.onEach { latestName = it }.launchIn(this)
            val levelJob = underTest.primaryLevel.onEach { latestLevel = it }.launchIn(this)

            underTest.setIsCarrierMerged(false)

            val operator1 = "Typical Merged Operator"
            val level1 = 1
            mobileRepo.operatorAlphaShort.value = operator1
            mobileRepo.primaryLevel.value = level1

            assertThat(latestName).isEqualTo(operator1)
            assertThat(latestLevel).isEqualTo(level1)

            val operator2 = "Typical Merged Operator #2"
            val level2 = 2
            mobileRepo.operatorAlphaShort.value = operator2
            mobileRepo.primaryLevel.value = level2

            assertThat(latestName).isEqualTo(operator2)
            assertThat(latestLevel).isEqualTo(level2)

            val operator3 = "Typical Merged Operator #3"
            val level3 = 3
            mobileRepo.operatorAlphaShort.value = operator3
            mobileRepo.primaryLevel.value = level3

            assertThat(latestName).isEqualTo(operator3)
            assertThat(latestLevel).isEqualTo(level3)

            nameJob.cancel()
            levelJob.cancel()
        }

    @Test
    fun connectionInfo_updatesWhenCarrierMergedUpdates() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            var latestName: String? = null
            var latestLevel: Int? = null

            val nameJob = underTest.operatorAlphaShort.onEach { latestName = it }.launchIn(this)
            val levelJob = underTest.primaryLevel.onEach { latestLevel = it }.launchIn(this)

            val carrierMergedOperator = "Carrier Merged Operator"
            val carrierMergedLevel = 4
            carrierMergedRepo.operatorAlphaShort.value = carrierMergedOperator
            carrierMergedRepo.primaryLevel.value = carrierMergedLevel

            val mobileName = "Typical Operator"
            val mobileLevel = 2
            mobileRepo.operatorAlphaShort.value = mobileName
            mobileRepo.primaryLevel.value = mobileLevel

            // Start with the mobile info
            assertThat(latestName).isEqualTo(mobileName)
            assertThat(latestLevel).isEqualTo(mobileLevel)

            // WHEN isCarrierMerged is set to true
            underTest.setIsCarrierMerged(true)

            // THEN the carrier merged info is used
            assertThat(latestName).isEqualTo(carrierMergedOperator)
            assertThat(latestLevel).isEqualTo(carrierMergedLevel)

            val newCarrierMergedName = "New CM Operator"
            val newCarrierMergedLevel = 0
            carrierMergedRepo.operatorAlphaShort.value = newCarrierMergedName
            carrierMergedRepo.primaryLevel.value = newCarrierMergedLevel

            assertThat(latestName).isEqualTo(newCarrierMergedName)
            assertThat(latestLevel).isEqualTo(newCarrierMergedLevel)

            // WHEN isCarrierMerged is set to false
            underTest.setIsCarrierMerged(false)

            // THEN the typical info is used
            assertThat(latestName).isEqualTo(mobileName)
            assertThat(latestLevel).isEqualTo(mobileLevel)

            val newMobileName = "New MobileOperator"
            val newMobileLevel = 3
            mobileRepo.operatorAlphaShort.value = newMobileName
            mobileRepo.primaryLevel.value = newMobileLevel

            assertThat(latestName).isEqualTo(newMobileName)
            assertThat(latestLevel).isEqualTo(newMobileLevel)

            nameJob.cancel()
            levelJob.cancel()
        }

    @Test
    fun isAllowedDuringAirplaneMode_updatesWhenCarrierMergedUpdates() =
        testScope.runTest {
            initializeRepo(startingIsCarrierMerged = false)

            val latest by collectLastValue(underTest.isAllowedDuringAirplaneMode)

            assertThat(latest).isFalse()

            underTest.setIsCarrierMerged(true)

            assertThat(latest).isTrue()

            underTest.setIsCarrierMerged(false)

            assertThat(latest).isFalse()
        }

    @Test
    fun factory_reusesLogBuffersForSameConnection() =
        testScope.runTest {
            val realLoggerFactory =
                TableLogBufferFactory(
                    mock(),
                    FakeSystemClock(),
                    mock(),
                    testDispatcher,
                    testScope.backgroundScope,
                )

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
                    subscriptionModel,
                    DEFAULT_NAME_MODEL,
                    SEP,
                )

            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = false,
                    subscriptionModel,
                    DEFAULT_NAME_MODEL,
                    SEP,
                )

            assertThat(connection1.tableLogBuffer)
                .isSameInstanceAs(connection1Repeat.tableLogBuffer)
        }

    @Test
    fun factory_reusesLogBuffersForSameSubIDevenIfCarrierMerged() =
        testScope.runTest {
            val realLoggerFactory =
                TableLogBufferFactory(
                    mock(),
                    FakeSystemClock(),
                    mock(),
                    testDispatcher,
                    testScope.backgroundScope,
                )

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
                    subscriptionModel,
                    DEFAULT_NAME_MODEL,
                    SEP,
                )

            // WHEN a connection with the same sub ID but carrierMerged = true is created
            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = true,
                    subscriptionModel,
                    DEFAULT_NAME_MODEL,
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
            val telephonyManager =
                mock<TelephonyManager>().apply { whenever(this.simOperatorName).thenReturn("") }
            createRealMobileRepo(telephonyManager)
            createRealCarrierMergedRepo(telephonyManager, FakeWifiRepository())

            initializeRepo(startingIsCarrierMerged = false)

            val emergencyJob = underTest.isEmergencyOnly.launchIn(this)
            val operatorJob = underTest.operatorAlphaShort.launchIn(this)

            // WHEN we set up some mobile connection info
            val serviceState = ServiceState()
            serviceState.setOperatorName("longName", "OpTypical", "1")
            serviceState.isEmergencyOnly = true
            getTelephonyCallbackForType<TelephonyCallback.ServiceStateListener>(telephonyManager)
                .onServiceStateChanged(serviceState)

            // THEN it's logged to the buffer
            assertThat(dumpBuffer()).contains("$COL_OPERATOR${BUFFER_SEPARATOR}OpTypical")
            assertThat(dumpBuffer()).contains("$COL_EMERGENCY${BUFFER_SEPARATOR}true")

            // WHEN we update mobile connection info
            val serviceState2 = ServiceState()
            serviceState2.setOperatorName("longName", "OpDiff", "1")
            serviceState2.isEmergencyOnly = false
            getTelephonyCallbackForType<TelephonyCallback.ServiceStateListener>(telephonyManager)
                .onServiceStateChanged(serviceState2)

            // THEN the updates are logged
            assertThat(dumpBuffer()).contains("$COL_OPERATOR${BUFFER_SEPARATOR}OpDiff")
            assertThat(dumpBuffer()).contains("$COL_EMERGENCY${BUFFER_SEPARATOR}false")

            emergencyJob.cancel()
            operatorJob.cancel()
        }

    @Test
    fun connectionInfo_logging_carrierMerged_getsUpdates() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            val telephonyManager =
                mock<TelephonyManager>().apply { whenever(this.simOperatorName).thenReturn("") }
            createRealMobileRepo(telephonyManager)
            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(telephonyManager, wifiRepository)

            initializeRepo(startingIsCarrierMerged = true)

            val job = underTest.primaryLevel.launchIn(this)

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
            val telephonyManager =
                mock<TelephonyManager>().apply { whenever(this.simOperatorName).thenReturn("") }
            createRealMobileRepo(telephonyManager)

            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(telephonyManager, wifiRepository)

            initializeRepo(startingIsCarrierMerged = false)

            val job = underTest.primaryLevel.launchIn(this)

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
            mobileRepo.primaryLevel.value = 0

            // THEN the new level is logged
            assertThat(dumpBuffer()).contains("$COL_PRIMARY_LEVEL${BUFFER_SEPARATOR}0")

            job.cancel()
        }

    @Test
    fun connectionInfo_logging_doesNotLogUpdatesForNotActiveRepo() =
        testScope.runTest {
            // SETUP: Use real repositories to verify the diffing still works. (See b/267501739.)
            val telephonyManager =
                mock<TelephonyManager>().apply { whenever(this.simOperatorName).thenReturn("") }
            createRealMobileRepo(telephonyManager)

            val wifiRepository = FakeWifiRepository()
            createRealCarrierMergedRepo(telephonyManager, wifiRepository)

            // WHEN isCarrierMerged = false
            initializeRepo(startingIsCarrierMerged = false)

            val job = underTest.primaryLevel.launchIn(this)

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
                subscriptionModel,
                DEFAULT_NAME_MODEL,
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
                SUB_ID,
                context,
                subscriptionModel,
                DEFAULT_NAME_MODEL,
                SEP,
                connectivityManager,
                telephonyManager,
                systemUiCarrierConfig = systemUiCarrierConfig,
                fakeBroadcastDispatcher,
                mobileMappingsProxy = mock(),
                testDispatcher,
                logger = mock(),
                tableLogBuffer,
                flags,
                testScope.backgroundScope,
            )
        whenever(
                mobileFactory.build(
                    eq(SUB_ID),
                    any(),
                    any(),
                    eq(DEFAULT_NAME_MODEL),
                    eq(SEP),
                )
            )
            .thenReturn(realRepo)

        return realRepo
    }

    private fun createRealCarrierMergedRepo(
        telephonyManager: TelephonyManager,
        wifiRepository: FakeWifiRepository,
    ): CarrierMergedConnectionRepository {
        wifiRepository.setIsWifiEnabled(true)
        wifiRepository.setIsWifiDefault(true)
        val realRepo =
            CarrierMergedConnectionRepository(
                SUB_ID,
                tableLogBuffer,
                telephonyManager,
                testScope.backgroundScope.coroutineContext,
                testScope.backgroundScope,
                wifiRepository,
            )
        whenever(
                carrierMergedFactory.build(
                    eq(SUB_ID),
                    any(),
                )
            )
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
        private const val DEFAULT_NAME = "default name"
        private val DEFAULT_NAME_MODEL = NetworkNameModel.Default(DEFAULT_NAME)
        private const val SEP = "-"
        private const val BUFFER_SEPARATOR = "|"
    }
}
