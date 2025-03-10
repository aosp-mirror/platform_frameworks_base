/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.qs.tiles.impl.hearingdevices.domain.interactor

import android.os.UserHandle
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.hearingaid.HearingDevicesChecker
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.tiles.base.interactor.DataUpdateTrigger
import com.android.systemui.qs.tiles.impl.hearingdevices.domain.model.HearingDevicesTileModel
import com.android.systemui.statusbar.policy.fakeBluetoothController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class HearingDevicesTileDataInteractorTest : SysuiTestCase() {
    private val kosmos = Kosmos()
    private val testScope = kosmos.testScope
    private val testUser = UserHandle.of(1)

    private val controller = kosmos.fakeBluetoothController
    private lateinit var underTest: HearingDevicesTileDataInteractor

    @Rule @JvmField val mockitoRule: MockitoRule = MockitoJUnit.rule()
    @Mock private lateinit var checker: HearingDevicesChecker

    @Before
    fun setup() {
        underTest = HearingDevicesTileDataInteractor(testScope.testScheduler, controller, checker)
    }

    @EnableFlags(Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    @Test
    fun availability_flagEnabled_returnTrue() =
        testScope.runTest {
            val availability by collectLastValue(underTest.availability(testUser))

            assertThat(availability).isTrue()
        }

    @DisableFlags(Flags.FLAG_HEARING_AIDS_QS_TILE_DIALOG)
    @Test
    fun availability_flagDisabled_returnFalse() =
        testScope.runTest {
            val availability by collectLastValue(underTest.availability(testUser))

            assertThat(availability).isFalse()
        }

    @Test
    fun tileData_bluetoothStateChanged_dataMatchesChecker() =
        testScope.runTest {
            val flowValues: List<HearingDevicesTileModel> by
                collectValues(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(flowValues.size).isEqualTo(1) // from addCallback in setup()

            whenever(checker.isAnyPairedHearingDevice).thenReturn(false)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(false)
            controller.isBluetoothEnabled = false
            runCurrent()
            assertThat(flowValues.size).isEqualTo(1) // model unchanged, no new flow value

            whenever(checker.isAnyPairedHearingDevice).thenReturn(true)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(false)
            controller.isBluetoothEnabled = true
            runCurrent()
            assertThat(flowValues.size).isEqualTo(2)

            whenever(checker.isAnyPairedHearingDevice).thenReturn(true)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(true)
            controller.isBluetoothEnabled = true
            runCurrent()
            assertThat(flowValues.size).isEqualTo(3)

            assertThat(flowValues.map { it.isAnyPairedHearingDevice })
                .containsExactly(false, true, true)
                .inOrder()
            assertThat(flowValues.map { it.isAnyActiveHearingDevice })
                .containsExactly(false, false, true)
                .inOrder()
        }

    @Test
    fun tileData_bluetoothDeviceChanged_dataMatchesChecker() =
        testScope.runTest {
            val flowValues: List<HearingDevicesTileModel> by
                collectValues(
                    underTest.tileData(testUser, flowOf(DataUpdateTrigger.InitialRequest))
                )
            runCurrent()
            assertThat(flowValues.size).isEqualTo(1) // from addCallback in setup()

            whenever(checker.isAnyPairedHearingDevice).thenReturn(false)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(false)
            controller.onBluetoothDevicesChanged()
            runCurrent()
            assertThat(flowValues.size).isEqualTo(1) // model unchanged, no new flow value

            whenever(checker.isAnyPairedHearingDevice).thenReturn(true)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(false)
            controller.onBluetoothDevicesChanged()
            runCurrent()
            assertThat(flowValues.size).isEqualTo(2)

            whenever(checker.isAnyPairedHearingDevice).thenReturn(true)
            whenever(checker.isAnyActiveHearingDevice).thenReturn(true)
            controller.onBluetoothDevicesChanged()
            runCurrent()
            assertThat(flowValues.size).isEqualTo(3)

            assertThat(flowValues.map { it.isAnyPairedHearingDevice })
                .containsExactly(false, true, true)
                .inOrder()
            assertThat(flowValues.map { it.isAnyActiveHearingDevice })
                .containsExactly(false, false, true)
                .inOrder()
        }
}
