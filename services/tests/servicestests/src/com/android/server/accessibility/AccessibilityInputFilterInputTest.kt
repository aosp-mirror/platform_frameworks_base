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

import android.hardware.display.DisplayManagerGlobal
import android.os.SystemClock
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import android.view.DisplayAdjustments
import android.view.DisplayInfo
import android.view.IInputFilterHost
import android.view.InputDevice.SOURCE_JOYSTICK
import android.view.InputDevice.SOURCE_STYLUS
import android.view.InputDevice.SOURCE_TOUCHSCREEN
import android.view.InputEvent
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_ENTER
import android.view.MotionEvent.ACTION_HOVER_EXIT
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.WindowManagerPolicyConstants.FLAG_PASS_TO_USER
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.cts.input.inputeventmatchers.withDeviceId
import com.android.cts.input.inputeventmatchers.withMotionAction
import com.android.cts.input.inputeventmatchers.withSource
import com.android.server.LocalServices
import com.android.server.accessibility.magnification.MagnificationProcessor
import com.android.server.wm.WindowManagerInternal
import java.util.concurrent.LinkedBlockingQueue
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.stubbing.OngoingStubbing


/**
 * Create a MotionEvent with the provided action, eventTime, and source
 */
fun createMotionEvent(action: Int, downTime: Long, eventTime: Long, source: Int, deviceId: Int):
        MotionEvent {
    val x = 1f
    val y = 2f
    val pressure = 3f
    val size = 1f
    val metaState = 0
    val xPrecision = 0f
    val yPrecision = 0f
    val edgeFlags = 0
    val displayId = 0
    return MotionEvent.obtain(downTime, eventTime, action, x, y, pressure, size, metaState,
        xPrecision, yPrecision, deviceId, edgeFlags, source, displayId)
}

/**
 * Tests for AccessibilityInputFilter, focusing on the input event processing as seen by the callers
 * of the InputFilter interface.
 * The main interaction with AccessibilityInputFilter in these tests is with the filterInputEvent
 * and sendInputEvent APIs of InputFilter.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityInputFilterInputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private companion object{
        const val ALL_A11Y_FEATURES = (AccessibilityInputFilter.FLAG_FEATURE_AUTOCLICK
                or AccessibilityInputFilter.FLAG_FEATURE_TOUCH_EXPLORATION
                or AccessibilityInputFilter.FLAG_FEATURE_CONTROL_SCREEN_MAGNIFIER
                or AccessibilityInputFilter.FLAG_FEATURE_TRIGGERED_SCREEN_MAGNIFIER
                or AccessibilityInputFilter.FLAG_FEATURE_INJECT_MOTION_EVENTS
                or AccessibilityInputFilter.FLAG_FEATURE_FILTER_KEY_EVENTS)
        const val STYLUS_SOURCE = SOURCE_STYLUS or SOURCE_TOUCHSCREEN
    }

    @Rule
    @JvmField
    val mocks: MockitoRule = MockitoJUnit.rule()

    @Rule
    @JvmField
    val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Mock
    private lateinit var mockA11yController: WindowManagerInternal.AccessibilityControllerInternal

    @Mock
    private lateinit var mockWindowManagerService: WindowManagerInternal

    @Mock
    private lateinit var mockMagnificationProcessor: MagnificationProcessor

    private val inputEvents = LinkedBlockingQueue<InputEvent>()
    private val verifier = BlockingQueueEventVerifier(inputEvents)

    @Mock
    private lateinit var host: IInputFilterHost
    private lateinit var ams: AccessibilityManagerService
    private lateinit var a11yInputFilter: AccessibilityInputFilter
    private val touchDeviceId = 1
    private val fromTouchScreen = allOf(withDeviceId(touchDeviceId), withSource(SOURCE_TOUCHSCREEN))
    private val stylusDeviceId = 2
    private val fromStylus = allOf(withDeviceId(stylusDeviceId), withSource(STYLUS_SOURCE))
    private val joystickDeviceId = 3
    private val fromJoystick = allOf(withDeviceId(joystickDeviceId), withSource(SOURCE_JOYSTICK))

    @Before
    fun setUp() {
        val context = instrumentation.context
        LocalServices.removeServiceForTest(WindowManagerInternal::class.java)
        LocalServices.addService(WindowManagerInternal::class.java, mockWindowManagerService)

        whenever(mockA11yController.isAccessibilityTracingEnabled).thenReturn(false)
        whenever(
            mockWindowManagerService.accessibilityController).thenReturn(
            mockA11yController)

        ams = Mockito.spy(AccessibilityManagerService(context))
        val displayList = arrayListOf(createStubDisplay(DEFAULT_DISPLAY, DisplayInfo()))
        whenever(ams.validDisplayList).thenReturn(displayList)
        whenever(ams.magnificationProcessor).thenReturn(mockMagnificationProcessor)

        doAnswer {
            val event = it.getArgument(0) as MotionEvent
            inputEvents.add(MotionEvent.obtain(event))
        }.`when`(host).sendInputEvent(any(), anyInt())

        a11yInputFilter = AccessibilityInputFilter(context, ams)
        a11yInputFilter.install(host)
    }

    @After
    fun tearDown() {
        if (this::a11yInputFilter.isInitialized) {
            a11yInputFilter.uninstall()
        }
    }

    /**
     * When no features are enabled, the events pass through the filter without getting modified.
     */
    @Test
    fun testSingleDeviceTouchEventsWithoutA11yFeatures() {
        enableFeatures(0)

        val downTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, downTime, downTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_DOWN)))

        sendTouchEvent(ACTION_MOVE, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_MOVE)))

        sendTouchEvent(ACTION_UP, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_UP)))

        verifier.assertNoEvents()
    }

    /**
     * Enable all a11y features and send a touchscreen stream of DOWN -> MOVE -> UP events.
     * These get converted into HOVER_ENTER -> HOVER_MOVE -> HOVER_EXIT events by the input filter.
     */
    @Test
    fun testSingleDeviceTouchEventsWithAllA11yFeatures() {
        enableFeatures(ALL_A11Y_FEATURES)

        val downTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, downTime, downTime)
        // DOWN event gets transformed to HOVER_ENTER
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        // MOVE becomes HOVER_MOVE
        sendTouchEvent(ACTION_MOVE, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_MOVE)))

        // UP becomes HOVER_EXIT
        sendTouchEvent(ACTION_UP, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_EXIT)))

        verifier.assertNoEvents()
    }

    /**
     * Enable all a11y features and send a touchscreen stream of DOWN -> CANCEL -> DOWN events.
     * These get converted into HOVER_ENTER -> HOVER_EXIT -> HOVER_ENTER events by the input filter.
     */
    @Test
    fun testTouchDownCancelDownWithAllA11yFeatures() {
        enableFeatures(ALL_A11Y_FEATURES)

        val downTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, downTime, downTime)
        // DOWN event gets transformed to HOVER_ENTER
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        // CANCEL becomes HOVER_EXIT
        sendTouchEvent(ACTION_CANCEL, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_EXIT)))

        // DOWN again! New hover is expected
        val newDownTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, newDownTime, newDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        verifier.assertNoEvents()
    }

    /**
     * Enable all a11y features and send a stylus stream of DOWN -> CANCEL -> DOWN events.
     * These get converted into HOVER_ENTER -> HOVER_EXIT -> HOVER_ENTER events by the input filter.
     * This test is the same as above, but for stylus events.
     */
    @Test
    fun testStylusDownCancelDownWithAllA11yFeatures() {
        enableFeatures(ALL_A11Y_FEATURES)

        val downTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, downTime, downTime)
        // DOWN event gets transformed to HOVER_ENTER
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        // CANCEL becomes HOVER_EXIT
        sendStylusEvent(ACTION_CANCEL, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_EXIT)))

        // DOWN again! New hover is expected
        val newDownTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, newDownTime, newDownTime)
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        verifier.assertNoEvents()
    }

    /**
     * Enable all a11y features and send a stylus stream and then a touch stream.
     */
    @Test
    fun testStylusThenTouch() {
        enableFeatures(ALL_A11Y_FEATURES)

        val downTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, downTime, downTime)
        // DOWN event gets transformed to HOVER_ENTER
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        // CANCEL becomes HOVER_EXIT
        sendStylusEvent(ACTION_CANCEL, downTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_EXIT)))

        val newDownTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, newDownTime, newDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        verifier.assertNoEvents()
    }

    /**
     * Enable all a11y features and send a touchscreen event stream. In the middle of the gesture,
     * disable the a11y features.
     * When the a11y features are disabled, the filter generates HOVER_EXIT without further input
     * from the dispatcher.
     */
    @Test
    fun testSingleDeviceTouchEventsDisableFeaturesMidGesture() {
        enableFeatures(ALL_A11Y_FEATURES)

        val downTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, downTime, downTime)

        // DOWN event gets transformed to HOVER_ENTER
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))
        verifier.assertNoEvents()

        enableFeatures(0)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_EXIT)))
        verifier.assertNoEvents()

        sendTouchEvent(ACTION_MOVE, downTime, SystemClock.uptimeMillis())
        sendTouchEvent(ACTION_UP, downTime, SystemClock.uptimeMillis())
        // As the original gesture continues, no additional events should be getting sent by the
        // filter because the HOVER_EXIT above already effectively finished the current gesture and
        // the DOWN event was never sent to the host.

        // Bug: the down event was swallowed, so the remainder of the gesture should be swallowed
        // too. However, the MOVE and UP events are currently passed back to the dispatcher.
        // TODO(b/310014874) - ensure a11y sends consistent input streams to the dispatcher
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_MOVE)))
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_UP)))

        verifier.assertNoEvents()
    }

    /**
     * Check multi-device behaviour when all a11y features are disabled. The events should pass
     * through unmodified, but only from the active (first) device.
     * The events from the inactive device should be dropped.
     * In this test, we are injecting a touchscreen event stream and a stylus event stream,
     * interleaved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HANDLE_MULTI_DEVICE_INPUT)
    fun testMultiDeviceEventsWithoutA11yFeatures() {
        enableFeatures(0)

        val touchDownTime = SystemClock.uptimeMillis()

        // Touch device - ACTION_DOWN
        sendTouchEvent(ACTION_DOWN, touchDownTime, touchDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_DOWN)))

        // Stylus device - ACTION_DOWN
        val stylusDownTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, stylusDownTime, stylusDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_CANCEL)))
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_DOWN)))

        // Touch device - ACTION_MOVE
        sendTouchEvent(ACTION_MOVE, touchDownTime, SystemClock.uptimeMillis())
        // Touch event is dropped
        verifier.assertNoEvents()

        // Stylus device - ACTION_MOVE
        sendStylusEvent(ACTION_MOVE, stylusDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_MOVE)))

        // Touch device - ACTION_UP
        sendTouchEvent(ACTION_UP, touchDownTime, SystemClock.uptimeMillis())
        // Touch event is dropped
        verifier.assertNoEvents()

        // Stylus device - ACTION_UP
        sendStylusEvent(ACTION_UP, stylusDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_UP)))

        verifier.assertNoEvents()
    }

    /**
     * Check multi-device behaviour when all a11y features are enabled. The events should be
     * modified accordingly, like DOWN events getting converted to hovers.
     * Only a single device should be active (the latest device to start a new gesture).
     * In this test, we are injecting a touchscreen event stream and a stylus event stream,
     * interleaved.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HANDLE_MULTI_DEVICE_INPUT)
    fun testMultiDeviceEventsWithAllA11yFeatures() {
        enableFeatures(ALL_A11Y_FEATURES)

        // Touch device - ACTION_DOWN
        val touchDownTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, touchDownTime, touchDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        // Stylus device - ACTION_DOWN
        val stylusDownTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, stylusDownTime, stylusDownTime)
        // Touch is canceled and stylus is started
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_EXIT)))
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        // Touch device - ACTION_MOVE
        sendTouchEvent(ACTION_MOVE, touchDownTime, SystemClock.uptimeMillis())
        // Stylus is active now; touch is ignored
        verifier.assertNoEvents()

        // Stylus device - ACTION_MOVE
        sendStylusEvent(ACTION_MOVE, stylusDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_MOVE)))

        // Touch device - ACTION_UP
        sendTouchEvent(ACTION_UP, touchDownTime, SystemClock.uptimeMillis())
        // Stylus is still active; touch is ignored
        verifier.assertNoEvents()

        sendStylusEvent(ACTION_UP, stylusDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_EXIT)))

        // Now stylus is done, and a new touch gesture will work!
        val newTouchDownTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, newTouchDownTime, newTouchDownTime)
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        verifier.assertNoEvents()
    }

    /**
     * Check multi-device behaviour when all a11y features are enabled. The events should be
     * modified accordingly, like DOWN events getting converted to hovers.
     * Only a single device should be active at a given time. The touch events start and end
     * while stylus is active. Check that the latest device is always given preference.
     */
    @Test
    @RequiresFlagsEnabled(Flags.FLAG_HANDLE_MULTI_DEVICE_INPUT)
    fun testStylusWithTouchInTheMiddle() {
        enableFeatures(ALL_A11Y_FEATURES)

        // Stylus device - ACTION_DOWN
        val stylusDownTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, stylusDownTime, stylusDownTime)
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        // Touch device - ACTION_DOWN
        val touchDownTime = SystemClock.uptimeMillis()
        sendTouchEvent(ACTION_DOWN, touchDownTime, touchDownTime)
        // Touch DOWN causes stylus to get canceled
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_EXIT)))
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_ENTER)))

        // Touch device - ACTION_MOVE
        sendTouchEvent(ACTION_MOVE, touchDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_MOVE)))

        sendStylusEvent(ACTION_MOVE, stylusDownTime, SystemClock.uptimeMillis())
        // Stylus is ignored because touch is active now
        verifier.assertNoEvents()

        sendTouchEvent(ACTION_UP, touchDownTime, SystemClock.uptimeMillis())
        verifier.assertReceivedMotion(allOf(fromTouchScreen, withMotionAction(ACTION_HOVER_EXIT)))

        sendStylusEvent(ACTION_UP, stylusDownTime, SystemClock.uptimeMillis())
        // The UP stylus event is also ignored
        verifier.assertNoEvents()

        // Now stylus works again, because touch gesture is finished
        val newStylusDownTime = SystemClock.uptimeMillis()
        sendStylusEvent(ACTION_DOWN, newStylusDownTime, newStylusDownTime)
        verifier.assertReceivedMotion(allOf(fromStylus, withMotionAction(ACTION_HOVER_ENTER)))

        verifier.assertNoEvents()
    }

    /**
     * Send some joystick events and ensure they pass through normally.
     */
    @Test
    fun testJoystickEvents() {
        enableFeatures(ALL_A11Y_FEATURES)

        sendJoystickEvent()
        verifier.assertReceivedMotion(fromJoystick)

        sendJoystickEvent()
        verifier.assertReceivedMotion(fromJoystick)

        sendJoystickEvent()
        verifier.assertReceivedMotion(fromJoystick)
    }

    private fun createStubDisplay(displayId: Int, displayInfo: DisplayInfo): Display {
        val display = Display(DisplayManagerGlobal.getInstance(), displayId,
            displayInfo, DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS)
        return display
    }

    private fun sendTouchEvent(action: Int, downTime: Long, eventTime: Long) {
        if (action == ACTION_DOWN) {
            assertEquals(downTime, eventTime)
        }
        send(createMotionEvent(action, downTime, eventTime, SOURCE_TOUCHSCREEN, touchDeviceId))
    }

    private fun sendStylusEvent(action: Int, downTime: Long, eventTime: Long) {
        if (action == ACTION_DOWN) {
            assertEquals(downTime, eventTime)
        }
        send(createMotionEvent(action, downTime, eventTime, STYLUS_SOURCE, stylusDeviceId))
    }

    private fun sendJoystickEvent() {
        val time = SystemClock.uptimeMillis()
        send(createMotionEvent(ACTION_MOVE, time, time, SOURCE_JOYSTICK, joystickDeviceId))
    }

    private fun send(event: InputEvent) {
        // We need to make a copy of the event before sending it to the filter, because the filter
        // will recycle it, but the caller of this function might want to still be able to use
        // this event for subsequent checks
        val eventCopy = if (event is MotionEvent) MotionEvent.obtain(event) else event
        a11yInputFilter.filterInputEvent(eventCopy, FLAG_PASS_TO_USER)
    }

    private fun enableFeatures(features: Int) {
        instrumentation.runOnMainSync { a11yInputFilter.setUserAndEnabledFeatures(0, features) }
    }
}

private fun <T> whenever(methodCall: T): OngoingStubbing<T> = `when`(methodCall)
