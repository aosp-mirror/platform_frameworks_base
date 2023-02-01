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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.log.table.TableLogBufferFactory
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.model.NetworkNameModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.MobileConnectionRepository
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val mobileMappings = FakeMobileMappingsProxy()
    private val tableLogBuffer = mock<TableLogBuffer>()
    private val mobileFactory = mock<MobileConnectionRepositoryImpl.Factory>()
    private val carrierMergedFactory = mock<CarrierMergedConnectionRepository.Factory>()

    private lateinit var connectionsRepo: FakeMobileConnectionsRepository
    private val globalMobileDataSettingChangedEvent: Flow<Unit>
        get() = connectionsRepo.globalMobileDataSettingChangedEvent

    private lateinit var mobileRepo: FakeMobileConnectionRepository
    private lateinit var carrierMergedRepo: FakeMobileConnectionRepository

    @Before
    fun setUp() {
        connectionsRepo = FakeMobileConnectionsRepository(mobileMappings, tableLogBuffer)

        mobileRepo = FakeMobileConnectionRepository(SUB_ID, tableLogBuffer)
        carrierMergedRepo = FakeMobileConnectionRepository(SUB_ID, tableLogBuffer)

        whenever(
                mobileFactory.build(
                    eq(SUB_ID),
                    any(),
                    eq(DEFAULT_NAME),
                    eq(SEP),
                    eq(globalMobileDataSettingChangedEvent),
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
                    globalMobileDataSettingChangedEvent
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
                    globalMobileDataSettingChangedEvent,
                )

            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = false,
                    DEFAULT_NAME,
                    SEP,
                    globalMobileDataSettingChangedEvent,
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
                    globalMobileDataSettingChangedEvent,
                )

            // WHEN a connection with the same sub ID but carrierMerged = true is created
            val connection1Repeat =
                factory.build(
                    SUB_ID,
                    startingIsCarrierMerged = true,
                    DEFAULT_NAME,
                    SEP,
                    globalMobileDataSettingChangedEvent,
                )

            // THEN the same table is re-used
            assertThat(connection1.tableLogBuffer)
                .isSameInstanceAs(connection1Repeat.tableLogBuffer)
        }

    // TODO(b/238425913): Verify that the logging switches correctly (once the carrier merged repo
    //   implements logging).

    private fun initializeRepo(startingIsCarrierMerged: Boolean) {
        underTest =
            FullMobileConnectionRepository(
                SUB_ID,
                startingIsCarrierMerged,
                tableLogBuffer,
                DEFAULT_NAME,
                SEP,
                globalMobileDataSettingChangedEvent,
                testScope.backgroundScope,
                mobileFactory,
                carrierMergedFactory,
            )
    }

    private companion object {
        const val SUB_ID = 42
        private val DEFAULT_NAME = NetworkNameModel.Default("default name")
        private const val SEP = "-"
    }
}
