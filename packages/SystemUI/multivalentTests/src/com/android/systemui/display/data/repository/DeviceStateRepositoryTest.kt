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

package com.android.systemui.display.data.repository

import android.hardware.devicestate.DeviceState.PROPERTY_EMULATED_ONLY
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT
import android.hardware.devicestate.DeviceState.PROPERTY_FEATURE_REAR_DISPLAY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
import android.hardware.devicestate.DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN
import android.hardware.devicestate.DeviceStateManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.DeviceStateRepository.DeviceState
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.whenever
import android.hardware.devicestate.DeviceState as PlatformDeviceState

@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
class DeviceStateRepositoryTest : SysuiTestCase() {

    private val deviceStateManager = mock<DeviceStateManager>()
    private val deviceStateManagerListener =
        kotlinArgumentCaptor<DeviceStateManager.DeviceStateCallback>()

    private val testScope = TestScope(UnconfinedTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var deviceStateRepository: DeviceStateRepositoryImpl

    @Before
    fun setup() {
        mContext.orCreateTestableResources.apply {
            addOverride(
                R.array.config_foldedDeviceStates,
                listOf(TEST_FOLDED.identifier).toIntArray()
            )
            addOverride(
                R.array.config_halfFoldedDeviceStates,
                TEST_HALF_FOLDED.identifier.toIntArray()
            )
            addOverride(R.array.config_openDeviceStates, TEST_UNFOLDED.identifier.toIntArray())
            addOverride(
                R.array.config_rearDisplayDeviceStates,
                TEST_REAR_DISPLAY.identifier.toIntArray()
            )
            addOverride(
                R.array.config_concurrentDisplayDeviceStates,
                TEST_CONCURRENT_DISPLAY.identifier.toIntArray()
            )
        }
        whenever(deviceStateManager.supportedDeviceStates).thenReturn(
            listOf(
                TEST_FOLDED,
                TEST_HALF_FOLDED,
                TEST_UNFOLDED,
                TEST_REAR_DISPLAY,
                TEST_CONCURRENT_DISPLAY
            )
        )
        deviceStateRepository =
            DeviceStateRepositoryImpl(
                mContext,
                deviceStateManager,
                TestScope(UnconfinedTestDispatcher()),
                fakeExecutor
            )

        // It should register only after there are clients collecting the flow
        verify(deviceStateManager, never()).registerCallback(any(), any())
    }

    @Test
    fun folded_receivesFoldedState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(TEST_FOLDED)

            assertThat(state()).isEqualTo(DeviceState.FOLDED)
        }

    @Test
    fun halfFolded_receivesHalfFoldedState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(TEST_HALF_FOLDED)

            assertThat(state()).isEqualTo(DeviceState.HALF_FOLDED)
        }

    @Test
    fun unfolded_receivesUnfoldedState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(TEST_UNFOLDED)

            assertThat(state()).isEqualTo(DeviceState.UNFOLDED)
        }

    @Test
    fun rearDisplay_receivesRearDisplayState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(TEST_REAR_DISPLAY)

            assertThat(state()).isEqualTo(DeviceState.REAR_DISPLAY)
        }

    @Test
    fun concurrentDisplay_receivesConcurrentDisplayState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(TEST_CONCURRENT_DISPLAY)

            assertThat(state()).isEqualTo(DeviceState.CONCURRENT_DISPLAY)
        }

    @Test
    fun unknownState_receivesUnknownState() =
        testScope.runTest {
            val state = displayState()

            deviceStateManagerListener.value.onDeviceStateChanged(
                getDeviceStateForIdentifier(123456)
            )

            assertThat(state()).isEqualTo(DeviceState.UNKNOWN)
        }

    private fun TestScope.displayState(): FlowValue<DeviceState?> {
        val flowValue = collectLastValue(deviceStateRepository.state)
        verify(deviceStateManager)
            .registerCallback(
                any(),
                deviceStateManagerListener.capture(),
            )
        return flowValue
    }

    private fun Int.toIntArray() = listOf(this).toIntArray()

    private fun getDeviceStateForIdentifier(id: Int): PlatformDeviceState {
        return PlatformDeviceState(
            PlatformDeviceState.Configuration.Builder(id, /* name= */ "")
                .build()
        )
    }

    private companion object {
        // Used to fake the ids in the test. Note that there is no guarantees different devices will
        // have the same ids (that's why the ones in this test start from 41)
        val TEST_FOLDED =
            PlatformDeviceState(
                PlatformDeviceState.Configuration.Builder(41, "FOLDED")
                    .setSystemProperties(
                        setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY)
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            )
        val TEST_HALF_FOLDED =
            PlatformDeviceState(
                PlatformDeviceState.Configuration.Builder(42, "HALF_FOLDED")
                    .setSystemProperties(
                        setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN)
                    )
                    .build()
            )
        val TEST_UNFOLDED =
            PlatformDeviceState(
                PlatformDeviceState.Configuration.Builder(43, "UNFOLDED")
                    .setSystemProperties(
                        setOf(PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY)
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)
                    )
                    .build()
            )
        val TEST_REAR_DISPLAY =
            PlatformDeviceState(
                PlatformDeviceState.Configuration.Builder(44, "REAR_DISPLAY")
                    .setSystemProperties(
                        setOf(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_OUTER_PRIMARY,
                            PROPERTY_FEATURE_REAR_DISPLAY,
                            PROPERTY_EMULATED_ONLY
                        )
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)
                    )
                    .build()
            )
        val TEST_CONCURRENT_DISPLAY =
            PlatformDeviceState(
                PlatformDeviceState.Configuration.Builder(45, "CONCURRENT_DISPLAY")
                    .setSystemProperties(
                        setOf(
                            PROPERTY_FOLDABLE_DISPLAY_CONFIGURATION_INNER_PRIMARY,
                            PROPERTY_FEATURE_DUAL_DISPLAY_INTERNAL_DEFAULT,
                            PROPERTY_EMULATED_ONLY
                        )
                    )
                    .setPhysicalProperties(
                        setOf(PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)
                    )
                    .build()
            )
    }
}
