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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.hardware.BatteryState.STATUS_CHARGING
import android.hardware.BatteryState.STATUS_FULL
import android.hardware.input.IInputDeviceBatteryListener
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.os.Binder
import android.os.IBinder
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import androidx.test.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.notNull
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit

/**
 * Tests for {@link InputDeviceBatteryController}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:InputDeviceBatteryControllerTests
 */
@Presubmit
class BatteryControllerTests {
    companion object {
        const val PID = 42
        const val DEVICE_ID = 13
        const val SECOND_DEVICE_ID = 11
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    @Mock
    private lateinit var native: NativeInputManagerService
    @Mock
    private lateinit var iInputManager: IInputManager

    private lateinit var batteryController: BatteryController
    private lateinit var context: Context

    @Before
    fun setup() {
        context = spy(ContextWrapper(InstrumentationRegistry.getContext()))
        val inputManager = InputManager.resetInstance(iInputManager)
        `when`(context.getSystemService(eq(Context.INPUT_SERVICE))).thenReturn(inputManager)
        `when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID, SECOND_DEVICE_ID))
        `when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(createInputDevice(DEVICE_ID))
        `when`(iInputManager.getInputDevice(SECOND_DEVICE_ID))
            .thenReturn(createInputDevice(SECOND_DEVICE_ID))

        batteryController = BatteryController(context, native)
    }

    private fun createInputDevice(deviceId: Int): InputDevice =
        InputDevice(deviceId, 0 /*generation*/, 0 /*controllerNumber*/,
            "Device $deviceId" /*name*/, 0 /*vendorId*/, 0 /*productId*/, "descriptor$deviceId",
            true /*isExternal*/, 0 /*sources*/, 0 /*keyboardType*/, null /*keyCharacterMap*/,
            false /*hasVibrator*/, false /*hasMicrophone*/, false /*hasButtonUnderPad*/,
            false /*hasSensor*/, true /*hasBattery*/)

    @After
    fun tearDown() {
        InputManager.clearInstance()
    }

    private fun createMockListener(): IInputDeviceBatteryListener {
        val listener = mock(IInputDeviceBatteryListener::class.java)
        val binder = mock(Binder::class.java)
        `when`(listener.asBinder()).thenReturn(binder)
        return listener
    }

    @Test
    fun testRegisterAndUnregisterBinderLifecycle() {
        val listener = createMockListener()
        // Ensure the binder lifecycle is tracked when registering a listener.
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).linkToDeath(notNull(), anyInt())
        batteryController.registerBatteryListener(SECOND_DEVICE_ID, listener, PID)
        verify(listener.asBinder(), times(1)).linkToDeath(notNull(), anyInt())

        // Ensure the binder lifecycle stops being tracked when all devices stopped being monitored.
        batteryController.unregisterBatteryListener(SECOND_DEVICE_ID, listener, PID)
        verify(listener.asBinder(), never()).unlinkToDeath(notNull(), anyInt())
        batteryController.unregisterBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).unlinkToDeath(notNull(), anyInt())
    }

    @Test
    fun testOneListenerPerProcess() {
        val listener1 = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener1, PID)
        verify(listener1.asBinder()).linkToDeath(notNull(), anyInt())

        // If a second listener is added for the same process, a security exception is thrown.
        val listener2 = createMockListener()
        try {
            batteryController.registerBatteryListener(DEVICE_ID, listener2, PID)
            fail("Expected security exception when registering more than one listener per process")
        } catch (ignored: SecurityException) {
        }
    }

    @Test
    fun testProcessDeathRemovesListener() {
        val deathRecipient = ArgumentCaptor.forClass(IBinder.DeathRecipient::class.java)
        val listener = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder()).linkToDeath(deathRecipient.capture(), anyInt())

        // When the binder dies, the callback is unregistered.
        deathRecipient.value!!.binderDied(listener.asBinder())
        verify(listener.asBinder()).unlinkToDeath(notNull(), anyInt())

        // It is now possible to register the same listener again.
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener.asBinder(), times(2)).linkToDeath(notNull(), anyInt())
    }

    @Test
    fun testRegisteringListenerNotifiesStateImmediately() {
        `when`(native.getBatteryStatus(DEVICE_ID)).thenReturn(STATUS_FULL)
        `when`(native.getBatteryCapacity(DEVICE_ID)).thenReturn(100)
        val listener = createMockListener()
        batteryController.registerBatteryListener(DEVICE_ID, listener, PID)
        verify(listener).onBatteryStateChanged(eq(DEVICE_ID), eq(true /*isPresent*/),
            eq(STATUS_FULL), eq(1f), anyLong())

        `when`(native.getBatteryStatus(SECOND_DEVICE_ID)).thenReturn(STATUS_CHARGING)
        `when`(native.getBatteryCapacity(SECOND_DEVICE_ID)).thenReturn(78)
        batteryController.registerBatteryListener(SECOND_DEVICE_ID, listener, PID)
        verify(listener).onBatteryStateChanged(eq(SECOND_DEVICE_ID), eq(true /*isPresent*/),
            eq(STATUS_CHARGING), eq(0.78f), anyLong())
    }
}
