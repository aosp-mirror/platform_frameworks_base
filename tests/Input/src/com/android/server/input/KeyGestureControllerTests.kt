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

package com.android.server.input

import android.content.Context
import android.content.ContextWrapper
import android.hardware.input.IInputManager
import android.hardware.input.AidlKeyGestureEvent
import android.hardware.input.IKeyGestureEventListener
import android.hardware.input.IKeyGestureHandler
import android.hardware.input.InputManager
import android.hardware.input.InputManagerGlobal
import android.hardware.input.KeyGestureEvent
import android.os.IBinder
import android.os.Process
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.view.InputDevice
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.android.internal.util.FrameworkStatsLog
import com.android.modules.utils.testing.ExtendedMockitoRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito

/**
 * Tests for {@link KeyGestureController}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyGestureControllerTests
 */
@Presubmit
class KeyGestureControllerTests {

    companion object {
        const val DEVICE_ID = 1
        val HOME_GESTURE_COMPLETE_EVENT = KeyGestureEvent.Builder()
            .setDeviceId(DEVICE_ID)
            .setKeycodes(intArrayOf(KeyEvent.KEYCODE_H))
            .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE)
            .build()
    }

    @JvmField
    @Rule
    val extendedMockitoRule = ExtendedMockitoRule.Builder(this)
        .mockStatic(FrameworkStatsLog::class.java).build()!!

    @Mock
    private lateinit var iInputManager: IInputManager

    private var currentPid = 0
    private lateinit var keyGestureController: KeyGestureController
    private lateinit var context: Context
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession
    private lateinit var testLooper: TestLooper
    private var events = mutableListOf<KeyGestureEvent>()

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManager)
        setupInputDevices()
        testLooper = TestLooper()
        currentPid = Process.myPid()
        keyGestureController = KeyGestureController(context, testLooper.looper)
    }

    private fun setupInputDevices() {
        val inputManager = InputManager(context)
        Mockito.`when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
            .thenReturn(inputManager)

        val keyboardDevice = InputDevice.Builder().setId(DEVICE_ID).build()
        Mockito.`when`(iInputManager.inputDeviceIds).thenReturn(intArrayOf(DEVICE_ID))
        Mockito.`when`(iInputManager.getInputDevice(DEVICE_ID)).thenReturn(keyboardDevice)
    }

    private fun notifyHomeGestureCompleted() {
        keyGestureController.notifyKeyGestureCompleted(DEVICE_ID, intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
    }

    @Test
    fun testKeyGestureEvent_registerUnregisterListener() {
        val listener = KeyGestureEventListener()

        // Register key gesture event listener
        keyGestureController.registerKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted()
        testLooper.dispatchAll()
        assertEquals(
            "Listener should get callbacks on key gesture event completed",
            1,
            events.size
        )
        assertEquals(
            "Listener should get callback for key gesture complete event",
            HOME_GESTURE_COMPLETE_EVENT,
            events[0]
        )

        // Unregister listener
        events.clear()
        keyGestureController.unregisterKeyGestureEventListener(listener, 0)
        notifyHomeGestureCompleted()
        testLooper.dispatchAll()
        assertEquals(
            "Listener should not get callback after being unregistered",
            0,
            events.size
        )
    }

    @Test
    fun testKeyGestureEvent_multipleGestureHandlers() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        var selfCallback = 0
        val externalHandler1 = KeyGestureHandler { _, _ ->
            callbackCount1++;
            true
        }
        val externalHandler2 = KeyGestureHandler { _, _ ->
            callbackCount2++;
            true
        }
        val selfHandler = KeyGestureHandler { _, _ ->
            selfCallback++;
            false
        }

        // Register key gesture handler: External process (last in priority)
        keyGestureController.registerKeyGestureHandler(externalHandler1, currentPid + 1)

        // Register key gesture handler: External process (second in priority)
        keyGestureController.registerKeyGestureHandler(externalHandler2, currentPid - 1)

        // Register key gesture handler: Self process (first in priority)
        keyGestureController.registerKeyGestureHandler(selfHandler, currentPid)

        keyGestureController.handleKeyGesture(/* deviceId = */ 0, intArrayOf(KeyEvent.KEYCODE_HOME),
            /* modifierState = */ 0, KeyGestureEvent.KEY_GESTURE_TYPE_HOME,
            KeyGestureEvent.ACTION_GESTURE_COMPLETE, /* displayId */ 0,
            /* focusedToken = */ null, /* flags = */ 0
        )

        assertEquals(
            "Self handler should get callbacks first",
            1,
            selfCallback
        )
        assertEquals(
            "Higher priority handler should get callbacks first",
            1,
            callbackCount2
        )
        assertEquals(
            "Lower priority handler should not get callbacks if already handled",
            0,
            callbackCount1
        )
    }

    inner class KeyGestureEventListener : IKeyGestureEventListener.Stub() {
        override fun onKeyGestureEvent(event: AidlKeyGestureEvent) {
            events.add(KeyGestureEvent(event))
        }
    }

    inner class KeyGestureHandler(
        private var handler: (event: AidlKeyGestureEvent, token: IBinder?) -> Boolean
    ) : IKeyGestureHandler.Stub() {
        override fun handleKeyGesture(event: AidlKeyGestureEvent, token: IBinder?): Boolean {
            return handler(event, token)
        }

        override fun isKeyGestureSupported(gestureType: Int): Boolean {
            return true
        }
    }
}