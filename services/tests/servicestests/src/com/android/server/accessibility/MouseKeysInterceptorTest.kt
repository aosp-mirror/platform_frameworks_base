/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility

import android.util.MathUtils.sqrt

import android.companion.virtual.VirtualDeviceManager
import android.companion.virtual.VirtualDeviceParams
import android.content.Context
import android.hardware.input.IInputManager
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.input.VirtualMouse
import android.hardware.input.VirtualMouseButtonEvent
import android.hardware.input.VirtualMouseConfig
import android.hardware.input.VirtualMouseRelativeEvent
import android.hardware.input.VirtualMouseScrollEvent
import android.os.RemoteException
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.android.server.companion.virtual.VirtualDeviceManagerInternal
import com.android.server.LocalServices
import com.android.server.testutils.OffsettableClock
import junit.framework.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import java.util.LinkedList
import java.util.Queue
import android.util.ArraySet
import android.view.InputDevice

/**
 * Tests for {@link MouseKeysInterceptor}
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:MouseKeysInterceptorTest
 */
@Presubmit
class MouseKeysInterceptorTest {
    companion object {
        const val DISPLAY_ID = 1
        const val DEVICE_ID = 123
        const val MOUSE_POINTER_MOVEMENT_STEP = 1.8f
        // This delay is required for key events to be sent and handled correctly.
        // The handler only performs a move/scroll event if it receives the key event
        // at INTERVAL_MILLIS (which happens in practice). Hence, we need this delay in the tests.
        const val KEYBOARD_POST_EVENT_DELAY_MILLIS = 20L
    }

    private lateinit var mouseKeysInterceptor: MouseKeysInterceptor
    private lateinit var inputDevice: InputDevice

    private val clock = OffsettableClock()
    private val testLooper = TestLooper { clock.now() }
    private val nextInterceptor = TrackingInterceptor()

    @Mock
    private lateinit var mockAms: AccessibilityManagerService

    @Mock
    private lateinit var iInputManager: IInputManager
    private lateinit var testSession: InputManagerGlobal.TestSession
    private lateinit var mockInputManager: InputManager

    @Mock
    private lateinit var mockVirtualDeviceManagerInternal: VirtualDeviceManagerInternal
    @Mock
    private lateinit var mockVirtualDevice: VirtualDeviceManager.VirtualDevice
    @Mock
    private lateinit var mockVirtualMouse: VirtualMouse

    @Mock
    private lateinit var mockTraceManager: AccessibilityTraceManager

    @Before
    @Throws(RemoteException::class)
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        val context = ApplicationProvider.getApplicationContext<Context>()
        testSession = InputManagerGlobal.createTestSession(iInputManager)
        mockInputManager = InputManager(context)

        inputDevice = createInputDevice(DEVICE_ID)
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID))
                .thenReturn(inputDevice)

        Mockito.`when`(mockVirtualDeviceManagerInternal.getDeviceIdsForUid(Mockito.anyInt()))
            .thenReturn(ArraySet(setOf(DEVICE_ID)))
        LocalServices.removeServiceForTest(VirtualDeviceManagerInternal::class.java)
        LocalServices.addService<VirtualDeviceManagerInternal>(
            VirtualDeviceManagerInternal::class.java, mockVirtualDeviceManagerInternal
        )

        Mockito.`when`(mockVirtualDeviceManagerInternal.createVirtualDevice(
            Mockito.any(VirtualDeviceParams::class.java)
        )).thenReturn(mockVirtualDevice)
        Mockito.`when`(mockVirtualDevice.createVirtualMouse(
            Mockito.any(VirtualMouseConfig::class.java)
        )).thenReturn(mockVirtualMouse)

        Mockito.`when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        Mockito.`when`(mockAms.traceManager).thenReturn(mockTraceManager)

        mouseKeysInterceptor = MouseKeysInterceptor(mockAms, mockInputManager,
                testLooper.looper, DISPLAY_ID)
        mouseKeysInterceptor.next = nextInterceptor
    }

    @After
    fun tearDown() {
        testLooper.dispatchAll()
        if (this::testSession.isInitialized) {
            testSession.close()
        }
    }

    @Test
    fun whenNonMouseKeyEventArrives_eventIsPassedToNextInterceptor() {
        val downTime = clock.now()
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_Q, 0, 0, DEVICE_ID, 0)
        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        assertEquals(1, nextInterceptor.events.size)
        assertEquals(downEvent, nextInterceptor.events.poll())
    }

    @Test
    fun whenMouseDirectionalKeyIsPressed_relativeEventIsSent() {
        // There should be some delay between the downTime of the key event and calling onKeyEvent
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCode = MouseKeysInterceptor.MouseKeyEvent.DIAGONAL_DOWN_LEFT_MOVE.keyCodeValue
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, DEVICE_ID, 0)

        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        // Verify the sendRelativeEvent method is called once and capture the arguments
        verifyRelativeEvents(arrayOf(-MOUSE_POINTER_MOVEMENT_STEP / sqrt(2.0f)),
            arrayOf(MOUSE_POINTER_MOVEMENT_STEP / sqrt(2.0f)))
    }

    @Test
    fun whenClickKeyIsPressed_buttonEventIsSent() {
        // There should be some delay between the downTime of the key event and calling onKeyEvent
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCode = MouseKeysInterceptor.MouseKeyEvent.LEFT_CLICK.keyCodeValue
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, DEVICE_ID, 0)
        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        val actions = arrayOf<Int>(
            VirtualMouseButtonEvent.ACTION_BUTTON_PRESS,
            VirtualMouseButtonEvent.ACTION_BUTTON_RELEASE)
        val buttons = arrayOf<Int>(
            VirtualMouseButtonEvent.BUTTON_PRIMARY,
            VirtualMouseButtonEvent.BUTTON_PRIMARY)
        // Verify the sendButtonEvent method is called twice and capture the arguments
        verifyButtonEvents(actions, buttons)
    }

    @Test
    fun whenHoldKeyIsPressed_buttonEventIsSent() {
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCode = MouseKeysInterceptor.MouseKeyEvent.HOLD.keyCodeValue
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, DEVICE_ID, 0)
        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        // Verify the sendButtonEvent method is called once and capture the arguments
        verifyButtonEvents(
            arrayOf<Int>( VirtualMouseButtonEvent.ACTION_BUTTON_PRESS),
            arrayOf<Int>( VirtualMouseButtonEvent.BUTTON_PRIMARY)
        )
    }

    @Test
    fun whenReleaseKeyIsPressed_buttonEventIsSent() {
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCode = MouseKeysInterceptor.MouseKeyEvent.RELEASE.keyCodeValue
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, DEVICE_ID, 0)
        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        // Verify the sendButtonEvent method is called once and capture the arguments
        verifyButtonEvents(
            arrayOf<Int>( VirtualMouseButtonEvent.ACTION_BUTTON_RELEASE),
            arrayOf<Int>( VirtualMouseButtonEvent.BUTTON_PRIMARY)
        )
    }

    @Test
    fun whenScrollToggleOn_ScrollUpKeyIsPressed_scrollEventIsSent() {
        // There should be some delay between the downTime of the key event and calling onKeyEvent
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCodeScrollToggle = MouseKeysInterceptor.MouseKeyEvent.SCROLL_TOGGLE.keyCodeValue
        val keyCodeScroll = MouseKeysInterceptor.MouseKeyEvent.UP_MOVE_OR_SCROLL.keyCodeValue

        val scrollToggleDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCodeScrollToggle, 0, 0, DEVICE_ID, 0)
        val scrollDownEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCodeScroll, 0, 0, DEVICE_ID, 0)

        mouseKeysInterceptor.onKeyEvent(scrollToggleDownEvent, 0)
        mouseKeysInterceptor.onKeyEvent(scrollDownEvent, 0)
        testLooper.dispatchAll()

        // Verify the sendScrollEvent method is called once and capture the arguments
        verifyScrollEvents(arrayOf<Float>(0f), arrayOf<Float>(1.0f))
    }

    @Test
    fun whenScrollToggleOff_DirectionalUpKeyIsPressed_RelativeEventIsSent() {
        // There should be some delay between the downTime of the key event and calling onKeyEvent
        val downTime = clock.now() - KEYBOARD_POST_EVENT_DELAY_MILLIS
        val keyCode = MouseKeysInterceptor.MouseKeyEvent.UP_MOVE_OR_SCROLL.keyCodeValue
        val downEvent = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN,
            keyCode, 0, 0, DEVICE_ID, 0)

        mouseKeysInterceptor.onKeyEvent(downEvent, 0)
        testLooper.dispatchAll()

        // Verify the sendRelativeEvent method is called once and capture the arguments
        verifyRelativeEvents(arrayOf<Float>(0f), arrayOf<Float>(-MOUSE_POINTER_MOVEMENT_STEP))
    }

    private fun verifyRelativeEvents(expectedX: Array<Float>, expectedY: Array<Float>) {
        assertEquals(expectedX.size, expectedY.size)
        val captor = ArgumentCaptor.forClass(VirtualMouseRelativeEvent::class.java)
        Mockito.verify(mockVirtualMouse, Mockito.times(expectedX.size))
            .sendRelativeEvent(captor.capture())

        for (i in expectedX.indices) {
            val captorEvent = captor.allValues[i]
            assertEquals(expectedX[i], captorEvent.relativeX)
            assertEquals(expectedY[i], captorEvent.relativeY)
        }
    }

    private fun verifyButtonEvents(actions: Array<Int>, buttons: Array<Int>) {
        assertEquals(actions.size, buttons.size)
        val captor = ArgumentCaptor.forClass(VirtualMouseButtonEvent::class.java)
        Mockito.verify(mockVirtualMouse, Mockito.times(actions.size))
                .sendButtonEvent(captor.capture())

        for (i in actions.indices) {
            val captorEvent = captor.allValues[i]
            assertEquals(actions[i], captorEvent.action)
            assertEquals(buttons[i], captorEvent.buttonCode)
        }
    }

    private fun verifyScrollEvents(xAxisMovements: Array<Float>, yAxisMovements: Array<Float>) {
        assertEquals(xAxisMovements.size, yAxisMovements.size)
        val captor = ArgumentCaptor.forClass(VirtualMouseScrollEvent::class.java)
        Mockito.verify(mockVirtualMouse, Mockito.times(xAxisMovements.size))
            .sendScrollEvent(captor.capture())

        for (i in xAxisMovements.indices) {
            val captorEvent = captor.allValues[i]
            assertEquals(xAxisMovements[i], captorEvent.xAxisMovement)
            assertEquals(yAxisMovements[i], captorEvent.yAxisMovement)
        }
    }

    private fun createInputDevice(
            deviceId: Int,
            generation: Int = -1
    ): InputDevice =
            InputDevice.Builder()
                    .setId(deviceId)
                    .setName("Device $deviceId")
                    .setDescriptor("descriptor $deviceId")
                    .setGeneration(generation)
                    .build()

    private class TrackingInterceptor : BaseEventStreamTransformation() {
        val events: Queue<KeyEvent> = LinkedList()

        override fun onKeyEvent(event: KeyEvent, policyFlags: Int) {
            events.add(event)
        }
    }
}
