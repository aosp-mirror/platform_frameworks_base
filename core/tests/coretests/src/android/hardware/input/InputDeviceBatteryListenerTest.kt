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
package android.hardware.input

import android.hardware.BatteryState
import android.os.Handler
import android.os.HandlerExecutor
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import com.android.server.testutils.any
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doAnswer
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for [InputManager.InputDeviceBatteryListener].
 *
 * Build/Install/Run:
 * atest FrameworksCoreTests:InputDeviceBatteryListenerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class InputDeviceBatteryListenerTest {
    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var testLooper: TestLooper
    private var registeredListener: IInputDeviceBatteryListener? = null
    private val monitoredDevices = mutableListOf<Int>()
    private lateinit var executor: Executor
    private lateinit var inputManager: InputManager

    @Mock
    private lateinit var iInputManagerMock: IInputManager

    @Before
    fun setUp() {
        testLooper = TestLooper()
        executor = HandlerExecutor(Handler(testLooper.looper))
        registeredListener = null
        monitoredDevices.clear()
        inputManager = InputManager.resetInstance(iInputManagerMock)

        // Handle battery listener registration.
        doAnswer {
            val deviceId = it.getArgument(0) as Int
            val listener = it.getArgument(1) as IInputDeviceBatteryListener
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered battery listener per process.
                fail("Trying to register a new listener when one already exists")
            }
            if (monitoredDevices.contains(deviceId)) {
                fail("Trying to start monitoring a device that was already being monitored")
            }
            monitoredDevices.add(deviceId)
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerBatteryListener(anyInt(), any())

        // Handle battery listener being unregistered.
        doAnswer {
            val deviceId = it.getArgument(0) as Int
            val listener = it.getArgument(1) as IInputDeviceBatteryListener
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            if (!monitoredDevices.remove(deviceId)) {
                fail("Trying to stop monitoring a device that is not being monitored")
            }
            if (monitoredDevices.isEmpty()) {
                registeredListener = null
            }
        }.`when`(iInputManagerMock).unregisterBatteryListener(anyInt(), any())
    }

    @After
    fun tearDown() {
        InputManager.clearInstance()
    }

    private fun notifyBatteryStateChanged(
        deviceId: Int,
        isPresent: Boolean = true,
        status: Int = BatteryState.STATUS_FULL,
        capacity: Float = 1.0f,
        eventTime: Long = 12345L
    ) {
        registeredListener!!.onBatteryStateChanged(deviceId, isPresent, status, capacity, eventTime)
    }

    @Test
    fun testListenerIsNotifiedCorrectly() {
        var callbackCount = 0

        // Add a battery listener to monitor battery changes.
        inputManager.addInputDeviceBatteryListener(1 /*deviceId*/, executor) {
                deviceId: Int, eventTime: Long, batteryState: BatteryState ->
            callbackCount++
            assertEquals(1, deviceId)
            assertEquals(true, batteryState.isPresent)
            assertEquals(BatteryState.STATUS_DISCHARGING, batteryState.status)
            assertEquals(0.5f, batteryState.capacity)
            assertEquals(8675309L, eventTime)
        }

        // Adding the listener should register the callback with InputManagerService.
        assertNotNull(registeredListener)
        assertTrue(monitoredDevices.contains(1))

        // Notifying battery change for a different device should not trigger the listener.
        notifyBatteryStateChanged(deviceId = 2)
        testLooper.dispatchAll()
        assertEquals(0, callbackCount)

        // Notifying battery change for the registered device will notify the listener.
        notifyBatteryStateChanged(1 /*deviceId*/, true /*isPresent*/,
            BatteryState.STATUS_DISCHARGING, 0.5f /*capacity*/, 8675309L /*eventTime*/)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount)
    }

    @Test
    fun testMultipleListeners() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.InputDeviceBatteryListener { _, _, _ -> callbackCount1++ }
        val callback2 = InputManager.InputDeviceBatteryListener { _, _, _ -> callbackCount2++ }

        // Monitor battery changes for three devices. The first callback monitors devices 1 and 3,
        // while the second callback monitors devices 2 and 3.
        inputManager.addInputDeviceBatteryListener(1 /*deviceId*/, executor, callback1)
        assertEquals(1, monitoredDevices.size)
        inputManager.addInputDeviceBatteryListener(2 /*deviceId*/, executor, callback2)
        assertEquals(2, monitoredDevices.size)
        inputManager.addInputDeviceBatteryListener(3 /*deviceId*/, executor, callback1)
        assertEquals(3, monitoredDevices.size)
        inputManager.addInputDeviceBatteryListener(3 /*deviceId*/, executor, callback2)
        assertEquals(3, monitoredDevices.size)

        // Notifying battery change for each of the devices should trigger the registered callbacks.
        notifyBatteryStateChanged(deviceId = 1)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount1)
        assertEquals(0, callbackCount2)

        notifyBatteryStateChanged(deviceId = 2)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount1)
        assertEquals(1, callbackCount2)

        notifyBatteryStateChanged(deviceId = 3)
        testLooper.dispatchNext()
        testLooper.dispatchNext()
        assertEquals(2, callbackCount1)
        assertEquals(2, callbackCount2)

        // Stop monitoring devices 1 and 2.
        inputManager.removeInputDeviceBatteryListener(1 /*deviceId*/, callback1)
        assertEquals(2, monitoredDevices.size)
        inputManager.removeInputDeviceBatteryListener(2 /*deviceId*/, callback2)
        assertEquals(1, monitoredDevices.size)

        // Ensure device 3 continues to be monitored.
        notifyBatteryStateChanged(deviceId = 3)
        testLooper.dispatchNext()
        testLooper.dispatchNext()
        assertEquals(3, callbackCount1)
        assertEquals(3, callbackCount2)

        // Stop monitoring all devices.
        inputManager.removeInputDeviceBatteryListener(3 /*deviceId*/, callback1)
        assertEquals(1, monitoredDevices.size)
        inputManager.removeInputDeviceBatteryListener(3 /*deviceId*/, callback2)
        assertEquals(0, monitoredDevices.size)
    }

    @Test
    fun testAdditionalListenersNotifiedImmediately() {
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.InputDeviceBatteryListener { _, _, _ -> callbackCount1++ }
        val callback2 = InputManager.InputDeviceBatteryListener { _, _, _ -> callbackCount2++ }

        // Add a battery listener and send the latest battery state.
        inputManager.addInputDeviceBatteryListener(1 /*deviceId*/, executor, callback1)
        assertEquals(1, monitoredDevices.size)
        notifyBatteryStateChanged(deviceId = 1)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount1)

        // Add a second listener for the same device that already has the latest battery state.
        inputManager.addInputDeviceBatteryListener(1 /*deviceId*/, executor, callback2)
        assertEquals(1, monitoredDevices.size)

        // Ensure that this listener is notified immediately.
        testLooper.dispatchNext()
        assertEquals(1, callbackCount2)
    }
}
