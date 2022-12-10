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

import android.telephony.Annotation
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import com.android.settingslib.SignalIcon
import com.android.settingslib.mobile.TelephonyIcons
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.mobile.data.model.DataConnectionState
import com.android.systemui.statusbar.pipeline.mobile.data.model.MobileConnectionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.demo.model.FakeNetworkEventModel
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

/**
 * Parameterized test for all of the common values of [FakeNetworkEventModel]. This test simply
 * verifies that passing the given model to [DemoMobileConnectionsRepository] results in the correct
 * flows emitting from the given connection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(Parameterized::class)
internal class DemoMobileConnectionParameterizedTest(private val testCase: TestCase) :
    SysuiTestCase() {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val fakeNetworkEventFlow = MutableStateFlow<FakeNetworkEventModel?>(null)

    private lateinit var connectionsRepo: DemoMobileConnectionsRepository
    private lateinit var underTest: DemoMobileConnectionRepository
    private lateinit var mockDataSource: DemoModeMobileConnectionDataSource

    @Before
    fun setUp() {
        // The data source only provides one API, so we can mock it with a flow here for convenience
        mockDataSource =
            mock<DemoModeMobileConnectionDataSource>().also {
                whenever(it.mobileEvents).thenReturn(fakeNetworkEventFlow)
            }

        connectionsRepo =
            DemoMobileConnectionsRepository(
                dataSource = mockDataSource,
                scope = testScope.backgroundScope,
                context = context,
            )

        connectionsRepo.startProcessingCommands()
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun demoNetworkData() =
        testScope.runTest {
            val networkModel =
                FakeNetworkEventModel.Mobile(
                    level = testCase.level,
                    dataType = testCase.dataType,
                    subId = testCase.subId,
                    carrierId = testCase.carrierId,
                    inflateStrength = testCase.inflateStrength,
                    activity = testCase.activity,
                    carrierNetworkChange = testCase.carrierNetworkChange,
                )

            fakeNetworkEventFlow.value = networkModel
            underTest = connectionsRepo.getRepoForSubId(subId)

            assertConnection(underTest, networkModel)
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
                assertThat(connectionInfo.dataActivityDirection).isEqualTo(model.activity)
                assertThat(connectionInfo.carrierNetworkChangeActive)
                    .isEqualTo(model.carrierNetworkChange)

                // TODO(b/261029387): check these once we start handling them
                assertThat(connectionInfo.isEmergencyOnly).isFalse()
                assertThat(connectionInfo.isGsm).isFalse()
                assertThat(connectionInfo.dataConnectionState)
                    .isEqualTo(DataConnectionState.Connected)
            }
            // MobileDisabled isn't combinatorial in nature, and is tested in
            // DemoMobileConnectionsRepositoryTest.kt
            else -> {}
        }
    }

    /** Matches [FakeNetworkEventModel] */
    internal data class TestCase(
        val level: Int,
        val dataType: SignalIcon.MobileIconGroup,
        val subId: Int,
        val carrierId: Int,
        val inflateStrength: Boolean,
        @Annotation.DataActivityType val activity: Int,
        val carrierNetworkChange: Boolean,
    ) {
        override fun toString(): String {
            return "INPUT(level=$level, " +
                "dataType=${dataType.name}, " +
                "subId=$subId, " +
                "carrierId=$carrierId, " +
                "inflateStrength=$inflateStrength, " +
                "activity=$activity, " +
                "carrierNetworkChange=$carrierNetworkChange)"
        }

        // Convenience for iterating test data and creating new cases
        fun modifiedBy(
            level: Int? = null,
            dataType: SignalIcon.MobileIconGroup? = null,
            subId: Int? = null,
            carrierId: Int? = null,
            inflateStrength: Boolean? = null,
            @Annotation.DataActivityType activity: Int? = null,
            carrierNetworkChange: Boolean? = null,
        ): TestCase =
            TestCase(
                level = level ?: this.level,
                dataType = dataType ?: this.dataType,
                subId = subId ?: this.subId,
                carrierId = carrierId ?: this.carrierId,
                inflateStrength = inflateStrength ?: this.inflateStrength,
                activity = activity ?: this.activity,
                carrierNetworkChange = carrierNetworkChange ?: this.carrierNetworkChange
            )
    }

    companion object {
        private val subId = 1

        private val booleanList = listOf(true, false)
        private val levels = listOf(0, 1, 2, 3)
        private val dataTypes =
            listOf(
                TelephonyIcons.THREE_G,
                TelephonyIcons.LTE,
                TelephonyIcons.FOUR_G,
                TelephonyIcons.NR_5G,
                TelephonyIcons.NR_5G_PLUS,
            )
        private val carrierIds = listOf(1, 10, 100)
        private val inflateStrength = booleanList
        private val activity =
            listOf(
                TelephonyManager.DATA_ACTIVITY_NONE,
                TelephonyManager.DATA_ACTIVITY_IN,
                TelephonyManager.DATA_ACTIVITY_OUT,
                TelephonyManager.DATA_ACTIVITY_INOUT
            )
        private val carrierNetworkChange = booleanList

        @Parameters(name = "{0}") @JvmStatic fun data() = testData()

        /**
         * Generate some test data. For the sake of convenience, we'll parameterize only non-null
         * network event data. So given the lists of test data:
         * ```
         *    list1 = [1, 2, 3]
         *    list2 = [false, true]
         *    list3 = [a, b, c]
         * ```
         * We'll generate test cases for:
         *
         * Test (1, false, a) Test (2, false, a) Test (3, false, a) Test (1, true, a) Test (1,
         * false, b) Test (1, false, c)
         *
         * NOTE: this is not a combinatorial product of all of the possible sets of parameters.
         * Since this test is built to exercise demo mode, the general approach is to define a
         * fully-formed "base case", and from there to make sure to use every valid parameter once,
         * by defining the rest of the test cases against the base case. Specific use-cases can be
         * added to the non-parameterized test, or manually below the generated test cases.
         */
        private fun testData(): List<TestCase> {
            val testSet = mutableSetOf<TestCase>()

            val baseCase =
                TestCase(
                    levels.first(),
                    dataTypes.first(),
                    subId,
                    carrierIds.first(),
                    inflateStrength.first(),
                    activity.first(),
                    carrierNetworkChange.first()
                )

            val tail =
                sequenceOf(
                        levels.map { baseCase.modifiedBy(level = it) },
                        dataTypes.map { baseCase.modifiedBy(dataType = it) },
                        carrierIds.map { baseCase.modifiedBy(carrierId = it) },
                        inflateStrength.map { baseCase.modifiedBy(inflateStrength = it) },
                        activity.map { baseCase.modifiedBy(activity = it) },
                        carrierNetworkChange.map { baseCase.modifiedBy(carrierNetworkChange = it) },
                    )
                    .flatten()

            testSet.add(baseCase)
            tail.toCollection(testSet)

            return testSet.toList()
        }
    }
}
