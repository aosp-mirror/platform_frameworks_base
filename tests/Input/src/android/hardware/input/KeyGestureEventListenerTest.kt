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
import android.os.HandlerExecutor
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
 * Tests for [InputManager.KeyGestureEventListener].
 *
 * Build/Install/Run:
 * atest InputTests:KeyGestureEventListenerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class KeyGestureEventListenerTest {

    companion object {
        const val DEVICE_ID = 1
        val HOME_GESTURE_EVENT = KeyGestureEvent.Builder()
            .setDeviceId(DEVICE_ID)
            .setKeycodes(intArrayOf(KeyEvent.KEYCODE_H))
            .setModifierState(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
    }

    @get:Rule
    val rule = SetFlagsRule()

    private val testLooper = TestLooper()
    private val executor = HandlerExecutor(Handler(testLooper.looper))
    private var registeredListener: IKeyGestureEventListener? = null
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

        // Handle key gesture event listener registration.
        doAnswer {
            val listener = it.getArgument(0) as IKeyGestureEventListener
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered key gesture event listener per process.
                fail("Trying to register a new listener when one already exists")
            }
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerKeyGestureEventListener(any())

        // Handle key gesture event listener being unregistered.
        doAnswer {
            val listener = it.getArgument(0) as IKeyGestureEventListener
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            registeredListener = null
            null
        }.`when`(iInputManagerMock).unregisterKeyGestureEventListener(any())
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun notifyKeyGestureEvent(event: KeyGestureEvent) {
        val eventToSend = AidlKeyGestureEvent()
        eventToSend.deviceId = event.deviceId
        eventToSend.keycodes = event.keycodes
        eventToSend.modifierState = event.modifierState
        eventToSend.gestureType = event.keyGestureType
        eventToSend.action = event.action
        eventToSend.displayId = event.displayId
        eventToSend.flags = event.flags
        registeredListener!!.onKeyGestureEvent(eventToSend)
    }

    @Test
    fun testListenerHasCorrectGestureNotified() {
        var callbackCount = 0

        // Add a key gesture event listener
        inputManager.registerKeyGestureEventListener(executor) {
            event: KeyGestureEvent ->
            assertEquals(HOME_GESTURE_EVENT, event)
            callbackCount++
        }

        // Notifying key gesture event will notify the listener.
        notifyKeyGestureEvent(HOME_GESTURE_EVENT)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount)
    }

    @Test
    fun testAddingListenersRegistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.KeyGestureEventListener { _ -> }
        val callback2 = InputManager.KeyGestureEventListener { _ -> }

        assertNull(registeredListener)

        // Adding the listener should register the callback with InputManagerService.
        inputManager.registerKeyGestureEventListener(executor, callback1)
        assertNotNull(registeredListener)

        // Adding another listener should not register new internal listener.
        val currListener = registeredListener
        inputManager.registerKeyGestureEventListener(executor, callback2)
        assertEquals(currListener, registeredListener)
    }

    @Test
    fun testRemovingListenersUnregistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.KeyGestureEventListener { _ -> }
        val callback2 = InputManager.KeyGestureEventListener { _ -> }

        inputManager.registerKeyGestureEventListener(executor, callback1)
        inputManager.registerKeyGestureEventListener(executor, callback2)

        // Only removing all listeners should remove the internal callback
        inputManager.unregisterKeyGestureEventListener(callback1)
        assertNotNull(registeredListener)
        inputManager.unregisterKeyGestureEventListener(callback2)
        assertNull(registeredListener)
    }

    @Test
    fun testMultipleListeners() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.KeyGestureEventListener { _ -> callbackCount1++ }
        val callback2 = InputManager.KeyGestureEventListener { _ -> callbackCount2++ }

        // Add both key gesture event listeners
        inputManager.registerKeyGestureEventListener(executor, callback1)
        inputManager.registerKeyGestureEventListener(executor, callback2)

        // Notifying key gesture event, should notify both the callbacks.
        notifyKeyGestureEvent(HOME_GESTURE_EVENT)
        testLooper.dispatchAll()
        assertEquals(1, callbackCount1)
        assertEquals(1, callbackCount2)

        inputManager.unregisterKeyGestureEventListener(callback2)
        // Notifying key gesture event, should still trigger callback1 but not
        // callback2.
        notifyKeyGestureEvent(HOME_GESTURE_EVENT)
        testLooper.dispatchAll()
        assertEquals(2, callbackCount1)
        assertEquals(1, callbackCount2)
    }
}
