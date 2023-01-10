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

import android.content.Context
import android.hardware.BatteryState
import android.hardware.input.InputManager
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@Ignore("TODO(b/20579491): unignore on main")
class StylusFirstUsageListenerTest : SysuiTestCase() {
    @Mock lateinit var context: Context
    @Mock lateinit var inputManager: InputManager
    @Mock lateinit var stylusManager: StylusManager
    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var internalStylusDevice: InputDevice
    @Mock lateinit var otherDevice: InputDevice
    @Mock lateinit var externalStylusDevice: InputDevice
    @Mock lateinit var batteryState: BatteryState
    @Mock lateinit var handler: Handler

    private lateinit var stylusListener: StylusFirstUsageListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)).thenReturn(true)
        whenever(inputManager.isStylusEverUsed(context)).thenReturn(false)

        stylusListener =
            StylusFirstUsageListener(
                context,
                inputManager,
                stylusManager,
                featureFlags,
                EXECUTOR,
                handler
            )
        stylusListener.hasStarted = false

        whenever(handler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        whenever(otherDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(false)
        whenever(internalStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(internalStylusDevice.isExternal).thenReturn(false)
        whenever(externalStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(externalStylusDevice.isExternal).thenReturn(true)

        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf())
        whenever(inputManager.getInputDevice(OTHER_DEVICE_ID)).thenReturn(otherDevice)
        whenever(inputManager.getInputDevice(INTERNAL_STYLUS_DEVICE_ID))
            .thenReturn(internalStylusDevice)
        whenever(inputManager.getInputDevice(EXTERNAL_STYLUS_DEVICE_ID))
            .thenReturn(externalStylusDevice)
    }

    @Test
    fun start_flagDisabled_doesNotRegister() {
        whenever(featureFlags.isEnabled(Flags.TRACK_STYLUS_EVER_USED)).thenReturn(false)

        stylusListener.start()

        verify(stylusManager, never()).registerCallback(any())
        verify(inputManager, never()).setStylusEverUsed(context, true)
    }

    @Test
    fun start_toggleHasStarted() {
        stylusListener.start()

        assert(stylusListener.hasStarted)
    }

    @Test
    fun start_hasStarted_doesNotRegister() {
        stylusListener.hasStarted = true

        stylusListener.start()

        verify(stylusManager, never()).registerCallback(any())
    }

    @Test
    fun start_hostDeviceDoesNotSupportStylus_doesNotRegister() {
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(OTHER_DEVICE_ID))

        stylusListener.start()

        verify(stylusManager, never()).registerCallback(any())
        verify(inputManager, never()).setStylusEverUsed(context, true)
    }

    @Test
    fun start_stylusEverUsed_doesNotRegister() {
        whenever(inputManager.inputDeviceIds)
            .thenReturn(intArrayOf(OTHER_DEVICE_ID, INTERNAL_STYLUS_DEVICE_ID))
        whenever(inputManager.isStylusEverUsed(context)).thenReturn(true)

        stylusListener.start()

        verify(stylusManager, never()).registerCallback(any())
        verify(inputManager, never()).setStylusEverUsed(context, true)
    }

    @Test
    fun start_hostDeviceSupportsStylus_registersListener() {
        whenever(inputManager.inputDeviceIds)
            .thenReturn(intArrayOf(OTHER_DEVICE_ID, INTERNAL_STYLUS_DEVICE_ID))

        stylusListener.start()

        verify(stylusManager).registerCallback(any())
        verify(inputManager, never()).setStylusEverUsed(context, true)
    }

    @Test
    fun onStylusAdded_hasNotStarted_doesNotRegisterListener() {
        stylusListener.hasStarted = false

        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        verifyZeroInteractions(inputManager)
    }

    @Test
    fun onStylusAdded_internalStylus_registersListener() {
        stylusListener.hasStarted = true

        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        verify(inputManager, times(1))
            .addInputDeviceBatteryListener(INTERNAL_STYLUS_DEVICE_ID, EXECUTOR, stylusListener)
    }

    @Test
    fun onStylusAdded_externalStylus_doesNotRegisterListener() {
        stylusListener.hasStarted = true

        stylusListener.onStylusAdded(EXTERNAL_STYLUS_DEVICE_ID)

        verify(inputManager, never()).addInputDeviceBatteryListener(any(), any(), any())
    }

    @Test
    fun onStylusAdded_otherDevice_doesNotRegisterListener() {
        stylusListener.onStylusAdded(OTHER_DEVICE_ID)

        verify(inputManager, never()).addInputDeviceBatteryListener(any(), any(), any())
    }

    @Test
    fun onStylusRemoved_registeredDevice_unregistersListener() {
        stylusListener.hasStarted = true
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        stylusListener.onStylusRemoved(INTERNAL_STYLUS_DEVICE_ID)

        verify(inputManager, times(1))
            .removeInputDeviceBatteryListener(INTERNAL_STYLUS_DEVICE_ID, stylusListener)
    }

    @Test
    fun onStylusRemoved_hasNotStarted_doesNotUnregisterListener() {
        stylusListener.hasStarted = false
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        stylusListener.onStylusRemoved(INTERNAL_STYLUS_DEVICE_ID)

        verifyZeroInteractions(inputManager)
    }

    @Test
    fun onStylusRemoved_unregisteredDevice_doesNotUnregisterListener() {
        stylusListener.hasStarted = true

        stylusListener.onStylusRemoved(INTERNAL_STYLUS_DEVICE_ID)

        verifyNoMoreInteractions(inputManager)
    }

    @Test
    fun onStylusBluetoothConnected_updateStylusFlagAndUnregisters() {
        stylusListener.hasStarted = true
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        stylusListener.onStylusBluetoothConnected(EXTERNAL_STYLUS_DEVICE_ID, "ANY")

        verify(inputManager).setStylusEverUsed(context, true)
        verify(inputManager, times(1))
            .removeInputDeviceBatteryListener(INTERNAL_STYLUS_DEVICE_ID, stylusListener)
        verify(stylusManager).unregisterCallback(stylusListener)
    }

    @Test
    fun onStylusBluetoothConnected_hasNotStarted_doesNoting() {
        stylusListener.hasStarted = false
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)

        stylusListener.onStylusBluetoothConnected(EXTERNAL_STYLUS_DEVICE_ID, "ANY")

        verifyZeroInteractions(inputManager)
        verifyZeroInteractions(stylusManager)
    }

    @Test
    fun onBatteryStateChanged_batteryPresent_updateStylusFlagAndUnregisters() {
        stylusListener.hasStarted = true
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)
        whenever(batteryState.isPresent).thenReturn(true)

        stylusListener.onBatteryStateChanged(0, 1, batteryState)

        verify(inputManager).setStylusEverUsed(context, true)
        verify(inputManager, times(1))
            .removeInputDeviceBatteryListener(INTERNAL_STYLUS_DEVICE_ID, stylusListener)
        verify(stylusManager).unregisterCallback(stylusListener)
    }

    @Test
    fun onBatteryStateChanged_batteryNotPresent_doesNotUpdateFlagOrUnregister() {
        stylusListener.hasStarted = true
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)
        whenever(batteryState.isPresent).thenReturn(false)

        stylusListener.onBatteryStateChanged(0, 1, batteryState)

        verifyZeroInteractions(stylusManager)
        verify(inputManager, never())
            .removeInputDeviceBatteryListener(INTERNAL_STYLUS_DEVICE_ID, stylusListener)
    }

    @Test
    fun onBatteryStateChanged_hasNotStarted_doesNothing() {
        stylusListener.hasStarted = false
        stylusListener.onStylusAdded(INTERNAL_STYLUS_DEVICE_ID)
        whenever(batteryState.isPresent).thenReturn(false)

        stylusListener.onBatteryStateChanged(0, 1, batteryState)

        verifyZeroInteractions(inputManager)
        verifyZeroInteractions(stylusManager)
    }

    companion object {
        private const val OTHER_DEVICE_ID = 0
        private const val INTERNAL_STYLUS_DEVICE_ID = 1
        private const val EXTERNAL_STYLUS_DEVICE_ID = 2
        private val EXECUTOR = FakeExecutor(FakeSystemClock())
    }
}
