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

import android.hardware.input.InputManager
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.view.InputDevice
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
@Ignore("b/257936830 until bt APIs")
class StylusManagerTest : SysuiTestCase() {
    @Mock lateinit var inputManager: InputManager

    @Mock lateinit var stylusDevice: InputDevice

    @Mock lateinit var btStylusDevice: InputDevice

    @Mock lateinit var otherDevice: InputDevice

    @Mock lateinit var handler: Handler

    @Mock lateinit var stylusCallback: StylusManager.StylusCallback

    @Mock lateinit var otherStylusCallback: StylusManager.StylusCallback

    private lateinit var stylusManager: StylusManager

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(handler.post(any())).thenAnswer {
            (it.arguments[0] as Runnable).run()
            true
        }

        stylusManager = StylusManager(inputManager, handler)

        stylusManager.registerCallback(stylusCallback)

        whenever(otherDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(false)
        whenever(stylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)
        whenever(btStylusDevice.supportsSource(InputDevice.SOURCE_STYLUS)).thenReturn(true)

        // whenever(stylusDevice.bluetoothAddress).thenReturn(null)
        // whenever(btStylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)

        whenever(inputManager.getInputDevice(OTHER_DEVICE_ID)).thenReturn(otherDevice)
        whenever(inputManager.getInputDevice(STYLUS_DEVICE_ID)).thenReturn(stylusDevice)
        whenever(inputManager.getInputDevice(BT_STYLUS_DEVICE_ID)).thenReturn(btStylusDevice)
        whenever(inputManager.inputDeviceIds).thenReturn(intArrayOf(STYLUS_DEVICE_ID))
    }

    @Test
    fun startListener_registersInputDeviceListener() {
        stylusManager.startListener()

        verify(inputManager, times(1)).registerInputDeviceListener(stylusManager, handler)
    }

    @Test
    fun onInputDeviceAdded_multipleRegisteredCallbacks_callsAll() {
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(stylusCallback)
        verify(otherStylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(otherStylusCallback)
    }

    @Test
    fun onInputDeviceAdded_stylus_callsCallbacksOnStylusAdded() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusAdded(STYLUS_DEVICE_ID)
        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceAdded_btStylus_callsCallbacksWithAddress() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        inOrder(stylusCallback).let {
            it.verify(stylusCallback, times(1)).onStylusAdded(BT_STYLUS_DEVICE_ID)
            it.verify(stylusCallback, times(1))
                .onStylusBluetoothConnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
        }
    }

    @Test
    fun onInputDeviceAdded_notStylus_doesNotCallCallbacks() {
        stylusManager.onInputDeviceAdded(OTHER_DEVICE_ID)

        verifyNoMoreInteractions(stylusCallback)
    }

    @Test
    fun onInputDeviceChanged_multipleRegisteredCallbacks_callsAll() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        // whenever(stylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
        verify(otherStylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_stylusNewBtConnection_callsCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        // whenever(stylusDevice.bluetoothAddress).thenReturn(STYLUS_BT_ADDRESS)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_stylusLostBtConnection_callsCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)
        // whenever(btStylusDevice.bluetoothAddress).thenReturn(null)

        stylusManager.onInputDeviceChanged(BT_STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothDisconnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_btConnection_stylusAlreadyBtConnected_onlyCallsListenersOnce() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceChanged(BT_STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1))
            .onStylusBluetoothConnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
    }

    @Test
    fun onInputDeviceChanged_noBtConnection_stylusNeverBtConnected_doesNotCallCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceChanged(STYLUS_DEVICE_ID)

        verify(stylusCallback, never()).onStylusBluetoothDisconnected(any(), any())
    }

    @Test
    fun onInputDeviceRemoved_multipleRegisteredCallbacks_callsAll() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)
        stylusManager.registerCallback(otherStylusCallback)

        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
        verify(otherStylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
    }

    @Test
    fun onInputDeviceRemoved_stylus_callsCallbacks() {
        stylusManager.onInputDeviceAdded(STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(STYLUS_DEVICE_ID)

        verify(stylusCallback, times(1)).onStylusRemoved(STYLUS_DEVICE_ID)
        verify(stylusCallback, never()).onStylusBluetoothDisconnected(any(), any())
    }

    @Test
    fun onInputDeviceRemoved_btStylus_callsCallbacks() {
        stylusManager.onInputDeviceAdded(BT_STYLUS_DEVICE_ID)

        stylusManager.onInputDeviceRemoved(BT_STYLUS_DEVICE_ID)

        inOrder(stylusCallback).let {
            it.verify(stylusCallback, times(1))
                .onStylusBluetoothDisconnected(BT_STYLUS_DEVICE_ID, STYLUS_BT_ADDRESS)
            it.verify(stylusCallback, times(1)).onStylusRemoved(BT_STYLUS_DEVICE_ID)
        }
    }

    companion object {
        private const val OTHER_DEVICE_ID = 0
        private const val STYLUS_DEVICE_ID = 1
        private const val BT_STYLUS_DEVICE_ID = 2

        private const val STYLUS_BT_ADDRESS = "SOME:ADDRESS"
    }
}
