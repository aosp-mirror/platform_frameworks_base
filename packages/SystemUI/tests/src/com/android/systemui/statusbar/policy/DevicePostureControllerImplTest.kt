/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.policy

import android.hardware.devicestate.DeviceState
import android.hardware.devicestate.DeviceStateManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableResources
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_CLOSED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_FLIPPED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_HALF_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_UNKNOWN
import com.android.systemui.statusbar.policy.DevicePostureController.SUPPORTED_POSTURES_SIZE
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DevicePostureControllerImplTest : SysuiTestCase() {
    private val useBaseStateDeviceState = SUPPORTED_POSTURES_SIZE
    private val deviceStateToPostureMapping =
        arrayOf(
            "$DEVICE_POSTURE_UNKNOWN:$DEVICE_POSTURE_UNKNOWN",
            "$DEVICE_POSTURE_CLOSED:$DEVICE_POSTURE_CLOSED",
            "$DEVICE_POSTURE_HALF_OPENED:$DEVICE_POSTURE_HALF_OPENED",
            "$DEVICE_POSTURE_OPENED:$DEVICE_POSTURE_OPENED",
            "$DEVICE_POSTURE_FLIPPED:$DEVICE_POSTURE_FLIPPED",
            "$useBaseStateDeviceState:1000"
        )
    @Mock private lateinit var deviceStateManager: DeviceStateManager
    @Captor
    private lateinit var deviceStateCallback: ArgumentCaptor<DeviceStateManager.DeviceStateCallback>

    private lateinit var mainExecutor: FakeExecutor
    private lateinit var testableResources: TestableResources
    private lateinit var underTest: DevicePostureControllerImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        mainExecutor = FakeExecutor(FakeSystemClock())
        testableResources = context.getOrCreateTestableResources()
        testableResources.addOverride(
            com.android.internal.R.array.config_device_state_postures,
            deviceStateToPostureMapping
        )
        whenever(deviceStateManager.supportedDeviceStates)
            .thenReturn(
                listOf(
                    DEVICE_STATE_CLOSED,
                    DEVICE_STATE_HALF_FOLDED,
                    DEVICE_STATE_OPENED,
                    DEVICE_STATE_FLIPPED,
                    DEVICE_STATE_UNKNOWN,
                    DEVICE_STATE_USE_BASE_STATE
                )
            )

        underTest =
            DevicePostureControllerImpl(
                context,
                deviceStateManager,
                mainExecutor,
            )
        verifyRegistersForDeviceStateCallback()
    }

    @Test
    fun testPostureChanged_updates() {
        var posture = -1
        underTest.addCallback { posture = it }

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_CLOSED)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_CLOSED)

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_HALF_OPENED)

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_OPENED)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_OPENED)

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_FLIPPED)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_FLIPPED)

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_UNKNOWN)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_UNKNOWN)
    }

    @Test
    fun testPostureChanged_useBaseUpdate() {
        var posture = -1
        underTest.addCallback { posture = it }

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_HALF_OPENED)

        val physicalProperties =
            setOf(DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
        val updatedState =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_STATE_HALF_FOLDED.identifier,
                        DEVICE_STATE_HALF_FOLDED.name
                    )
                    .setPhysicalProperties(physicalProperties)
                    .build()
            )
        // state change with updated physical properties shouldn't cause a posture change
        deviceStateCallback.value.onDeviceStateChanged(updatedState)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_HALF_OPENED)

        // WHEN the display state maps to the physical state, then posture updates
        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_USE_BASE_STATE)
        assertThat(posture).isEqualTo(DEVICE_POSTURE_CLOSED)
    }

    @Test
    fun baseStateChanges_doesNotUpdatePosture() {
        var numPostureChanges = 0
        underTest.addCallback { numPostureChanges++ }

        deviceStateCallback.value.onDeviceStateChanged(DEVICE_STATE_HALF_FOLDED)
        assertThat(numPostureChanges).isEqualTo(1)

        // update to physical properties doesn't send another posture update since the device state
        // isn't useBaseStateDeviceState
        deviceStateCallback.value.onDeviceStateChanged(
            getStateUpdatedPhysicalProperties(DEVICE_STATE_HALF_FOLDED, DEVICE_STATE_CLOSED)
        )
        deviceStateCallback.value.onDeviceStateChanged(
            getStateUpdatedPhysicalProperties(DEVICE_STATE_HALF_FOLDED, DEVICE_STATE_HALF_FOLDED)
        )
        deviceStateCallback.value.onDeviceStateChanged(
            getStateUpdatedPhysicalProperties(DEVICE_STATE_HALF_FOLDED, DEVICE_STATE_OPENED)
        )
        deviceStateCallback.value.onDeviceStateChanged(
            getStateUpdatedPhysicalProperties(DEVICE_STATE_HALF_FOLDED, DEVICE_STATE_UNKNOWN)
        )
        assertThat(numPostureChanges).isEqualTo(1)
    }

    private fun verifyRegistersForDeviceStateCallback() {
        verify(deviceStateManager).registerCallback(eq(mainExecutor), deviceStateCallback.capture())
    }

    private fun getStateUpdatedPhysicalProperties(
        currentState: DeviceState,
        physicalState: DeviceState
    ): DeviceState {
        return DeviceState(
            DeviceState.Configuration.Builder(currentState.identifier, currentState.name)
                .setSystemProperties(currentState.configuration.systemProperties)
                .setPhysicalProperties(physicalState.configuration.physicalProperties)
                .build()
        )
    }

    companion object {
        val DEVICE_STATE_CLOSED =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_POSTURE_CLOSED /* id */,
                        "CLOSED" /* name */
                    )
                    .setPhysicalProperties(
                        setOf(DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_CLOSED)
                    )
                    .build()
            )
        val DEVICE_STATE_HALF_FOLDED =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_POSTURE_HALF_OPENED /* id */,
                        "HALF_FOLDED" /* name */
                    )
                    .setPhysicalProperties(
                        setOf(
                            DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_HALF_OPEN
                        )
                    )
                    .build()
            )
        val DEVICE_STATE_OPENED =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_POSTURE_OPENED /* id */,
                        "OPENED" /* name */
                    )
                    .setPhysicalProperties(
                        setOf(DeviceState.PROPERTY_FOLDABLE_HARDWARE_CONFIGURATION_FOLD_IN_OPEN)
                    )
                    .build()
            )
        val DEVICE_STATE_FLIPPED =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_POSTURE_FLIPPED /* id */,
                        "FLIPPED" /* name */
                    )
                    .build()
            )
        val DEVICE_STATE_UNKNOWN =
            DeviceState(
                DeviceState.Configuration.Builder(
                        DEVICE_POSTURE_UNKNOWN /* id */,
                        "UNKNOWN" /* name */
                    )
                    .build()
            )
        val DEVICE_STATE_USE_BASE_STATE =
            DeviceState(
                DeviceState.Configuration.Builder(SUPPORTED_POSTURES_SIZE, "USE_BASE_STATE").build()
            )
    }
}
