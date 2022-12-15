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

package com.android.systemui.stylus

import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.whenever
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class StylusUsiPowerStartableTest : SysuiTestCase() {
    @Mock lateinit var inputManager: InputManager
    @Mock lateinit var stylusManager: StylusManager
    @Mock lateinit var stylusDevice: InputDevice
    @Mock lateinit var externalDevice: InputDevice
    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var stylusUsiPowerUi: StylusUsiPowerUI

    lateinit var startable: StylusUsiPowerStartable

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        startable =
            StylusUsiPowerStartable(
                stylusManager,
                inputManager,
                stylusUsiPowerUi,
                featureFlags,
                DIRECT_EXECUTOR,
            )

        whenever(featureFlags.isEnabled(Flags.ENABLE_USI_BATTERY_NOTIFICATIONS)).thenReturn(true)

        whenever(inputManager.getInputDevice(EXTERNAL_DEVICE_ID)).thenReturn(externalDevice)
        whenever(inputManager.getInputDevice(STYLUS_DEVICE_ID)).thenReturn(stylusDevice)
        whenever(inputManager.inputDeviceIds)
            .thenReturn(intArrayOf(EXTERNAL_DEVICE_ID, STYLUS_DEVICE_ID))

        whenever(stylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(stylusDevice.isExternal).thenReturn(false)
        whenever(stylusDevice.id).thenReturn(STYLUS_DEVICE_ID)
        whenever(externalDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(externalDevice.isExternal).thenReturn(true)
        whenever(externalDevice.id).thenReturn(EXTERNAL_DEVICE_ID)
    }

    @Test
    fun start_addsBatteryListenerForInternalStylus() {
        startable.start()

        verify(inputManager, times(1))
            .addInputDeviceBatteryListener(STYLUS_DEVICE_ID, DIRECT_EXECUTOR, startable)
    }

    @Test
    fun onStylusAdded_internalStylus_addsBatteryListener() {
        startable.onStylusAdded(STYLUS_DEVICE_ID)

        verify(inputManager, times(1))
            .addInputDeviceBatteryListener(STYLUS_DEVICE_ID, DIRECT_EXECUTOR, startable)
    }

    @Test
    fun onStylusAdded_externalStylus_doesNotAddBatteryListener() {
        startable.onStylusAdded(EXTERNAL_DEVICE_ID)

        verify(inputManager, never())
            .addInputDeviceBatteryListener(EXTERNAL_DEVICE_ID, DIRECT_EXECUTOR, startable)
    }

    @Test
    fun onStylusRemoved_registeredStylus_removesBatteryListener() {
        startable.onStylusAdded(STYLUS_DEVICE_ID)
        startable.onStylusRemoved(STYLUS_DEVICE_ID)

        inOrder(inputManager).let {
            it.verify(inputManager, times(1))
                .addInputDeviceBatteryListener(STYLUS_DEVICE_ID, DIRECT_EXECUTOR, startable)
            it.verify(inputManager, times(1))
                .removeInputDeviceBatteryListener(STYLUS_DEVICE_ID, startable)
        }
    }

    @Test
    fun onStylusBluetoothConnected_refreshesNotification() {
        startable.onStylusBluetoothConnected(STYLUS_DEVICE_ID, "ANY")

        verify(stylusUsiPowerUi, times(1)).refresh()
    }

    @Test
    fun onStylusBluetoothDisconnected_refreshesNotification() {
        startable.onStylusBluetoothDisconnected(STYLUS_DEVICE_ID, "ANY")

        verify(stylusUsiPowerUi, times(1)).refresh()
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_refreshesNotification() {
        val batteryState = mock(BatteryState::class.java)
        whenever(batteryState.isPresent).thenReturn(true)

        startable.onBatteryStateChanged(STYLUS_DEVICE_ID, 123, batteryState)

        verify(stylusUsiPowerUi, times(1)).updateBatteryState(batteryState)
    }

    @Test
    fun onBatteryStateChanged_batteryNotPresent_noop() {
        val batteryState = mock(BatteryState::class.java)
        whenever(batteryState.isPresent).thenReturn(false)

        startable.onBatteryStateChanged(STYLUS_DEVICE_ID, 123, batteryState)

        verifyNoMoreInteractions(stylusUsiPowerUi)
    }

    companion object {
        private val DIRECT_EXECUTOR = Executor { r -> r.run() }

        private const val EXTERNAL_DEVICE_ID = 0
        private const val STYLUS_DEVICE_ID = 1
    }
}
