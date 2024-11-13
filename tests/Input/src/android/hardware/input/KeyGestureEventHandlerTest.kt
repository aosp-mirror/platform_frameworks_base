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

package android.hardware.input

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.IBinder
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.android.server.testutils.any
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnitRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Tests for [InputManager.KeyGestureEventHandler].
 *
 * Build/Install/Run:
 * atest InputTests:KeyGestureEventHandlerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class KeyGestureEventHandlerTest {

    companion object {
        const val DEVICE_ID = 1
        val HOME_GESTURE_EVENT = KeyGestureEvent.Builder()
            .setDeviceId(DEVICE_ID)
            .setKeycodes(intArrayOf(KeyEvent.KEYCODE_H))
            .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        val BACK_GESTURE_EVENT = KeyGestureEvent.Builder()
            .setDeviceId(DEVICE_ID)
            .setKeycodes(intArrayOf(KeyEvent.KEYCODE_DEL))
            .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
            .build()
    }

    @get:Rule
    val rule = SetFlagsRule()

    private val testLooper = TestLooper()
    private var registeredListener: IKeyGestureHandler? = null
    private lateinit var context: Context
    private lateinit var inputManager: InputManager
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession

    @Mock
    private lateinit var iInputManagerMock: IInputManager

    @Before
    fun setUp() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManagerMock)
        inputManager = InputManager(context)
        `when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
                .thenReturn(inputManager)

        // Handle key gesture handler registration.
        doAnswer {
            val listener = it.getArgument(0) as IKeyGestureHandler
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered key gesture handler per process.
                fail("Trying to register a new listener when one already exists")
            }
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerKeyGestureHandler(any())

        // Handle key gesture handler being unregistered.
        doAnswer {
            val listener = it.getArgument(0) as IKeyGestureHandler
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            registeredListener = null
            null
        }.`when`(iInputManagerMock).unregisterKeyGestureHandler(any())
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun handleKeyGestureEvent(event: KeyGestureEvent) {
        val eventToSend = AidlKeyGestureEvent()
        eventToSend.deviceId = event.deviceId
        eventToSend.keycodes = event.keycodes
        eventToSend.modifierState = event.modifierState
        eventToSend.gestureType = event.keyGestureType
        eventToSend.action = event.action
        eventToSend.displayId = event.displayId
        eventToSend.flags = event.flags
        registeredListener!!.handleKeyGesture(eventToSend, null)
    }

    @Test
    fun testHandlerHasCorrectGestureNotified() {
        var callbackCount = 0

        // Add a key gesture event listener
        inputManager.registerKeyGestureEventHandler(KeyGestureHandler { event, _ ->
            assertEquals(HOME_GESTURE_EVENT, event)
            callbackCount++
            true
        })

        // Request handling for key gesture event will notify the handler.
        handleKeyGestureEvent(HOME_GESTURE_EVENT)
        assertEquals(1, callbackCount)
    }

    @Test
    fun testAddingHandlersRegistersInternalCallbackHandler() {
        // Set up two callbacks.
        val callback1 = KeyGestureHandler { _, _ -> false }
        val callback2 = KeyGestureHandler { _, _ -> false }

        assertNull(registeredListener)

        // Adding the handler should register the callback with InputManagerService.
        inputManager.registerKeyGestureEventHandler(callback1)
        assertNotNull(registeredListener)

        // Adding another handler should not register new internal listener.
        val currListener = registeredListener
        inputManager.registerKeyGestureEventHandler(callback2)
        assertEquals(currListener, registeredListener)
    }

    @Test
    fun testRemovingHandlersUnregistersInternalCallbackHandler() {
        // Set up two callbacks.
        val callback1 = KeyGestureHandler { _, _ -> false }
        val callback2 = KeyGestureHandler { _, _ -> false }

        inputManager.registerKeyGestureEventHandler(callback1)
        inputManager.registerKeyGestureEventHandler(callback2)

        // Only removing all handlers should remove the internal callback
        inputManager.unregisterKeyGestureEventHandler(callback1)
        assertNotNull(registeredListener)
        inputManager.unregisterKeyGestureEventHandler(callback2)
        assertNull(registeredListener)
    }

    @Test
    fun testMultipleHandlers() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        // Handler 1 captures all home gestures
        val callback1 = KeyGestureHandler { event, _ ->
            callbackCount1++
            event.keyGestureType == KeyGestureEvent.KEY_GESTURE_TYPE_HOME
        }
        // Handler 2 captures all gestures
        val callback2 = KeyGestureHandler { _, _ ->
            callbackCount2++
            true
        }

        // Add both key gesture event handlers
        inputManager.registerKeyGestureEventHandler(callback1)
        inputManager.registerKeyGestureEventHandler(callback2)

        // Request handling for key gesture event, should notify callbacks in order. So, only the
        // first handler should receive a callback since it captures the event.
        handleKeyGestureEvent(HOME_GESTURE_EVENT)
        assertEquals(1, callbackCount1)
        assertEquals(0, callbackCount2)

        // Second handler should receive the event since the first handler doesn't capture the event
        handleKeyGestureEvent(BACK_GESTURE_EVENT)
        assertEquals(2, callbackCount1)
        assertEquals(1, callbackCount2)

        inputManager.unregisterKeyGestureEventHandler(callback1)
        // Request handling for key gesture event, should still trigger callback2 but not callback1.
        handleKeyGestureEvent(HOME_GESTURE_EVENT)
        assertEquals(2, callbackCount1)
        assertEquals(2, callbackCount2)
    }

    inner class KeyGestureHandler(
        private var handler: (event: KeyGestureEvent, token: IBinder?) -> Boolean
    ) : InputManager.KeyGestureEventHandler {

        override fun handleKeyGestureEvent(
            event: KeyGestureEvent,
            focusedToken: IBinder?
        ): Boolean {
            return handler(event, focusedToken)
        }

        override fun isKeyGestureSupported(gestureType: Int): Boolean {
            return true
        }
    }
}
