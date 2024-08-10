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
 * Tests for [InputManager.KeyboardSystemShortcutListener].
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardSystemShortcutListenerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class KeyboardSystemShortcutListenerTest {

    companion object {
        const val DEVICE_ID = 1
        val HOME_SHORTCUT = KeyboardSystemShortcut(
            intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyboardSystemShortcut.SYSTEM_SHORTCUT_HOME
        )
    }

    @get:Rule
    val rule = SetFlagsRule()

    private val testLooper = TestLooper()
    private val executor = HandlerExecutor(Handler(testLooper.looper))
    private var registeredListener: IKeyboardSystemShortcutListener? = null
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

        // Handle keyboard system shortcut listener registration.
        doAnswer {
            val listener = it.getArgument(0) as IKeyboardSystemShortcutListener
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered keyboard system shortcut listener per process.
                fail("Trying to register a new listener when one already exists")
            }
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerKeyboardSystemShortcutListener(any())

        // Handle keyboard system shortcut listener being unregistered.
        doAnswer {
            val listener = it.getArgument(0) as IKeyboardSystemShortcutListener
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            registeredListener = null
            null
        }.`when`(iInputManagerMock).unregisterKeyboardSystemShortcutListener(any())
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun notifyKeyboardSystemShortcutTriggered(id: Int, shortcut: KeyboardSystemShortcut) {
        registeredListener!!.onKeyboardSystemShortcutTriggered(
            id,
            shortcut.keycodes,
            shortcut.modifierState,
            shortcut.systemShortcut
        )
    }

    @Test
    fun testListenerHasCorrectSystemShortcutNotified() {
        var callbackCount = 0

        // Add a keyboard system shortcut listener
        inputManager.registerKeyboardSystemShortcutListener(executor) {
            deviceId: Int, systemShortcut: KeyboardSystemShortcut ->
            assertEquals(DEVICE_ID, deviceId)
            assertEquals(HOME_SHORTCUT, systemShortcut)
            callbackCount++
        }

        // Notifying keyboard system shortcut triggered will notify the listener.
        notifyKeyboardSystemShortcutTriggered(DEVICE_ID, HOME_SHORTCUT)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount)
    }

    @Test
    fun testAddingListenersRegistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.KeyboardSystemShortcutListener {_, _ -> }
        val callback2 = InputManager.KeyboardSystemShortcutListener {_, _ -> }

        assertNull(registeredListener)

        // Adding the listener should register the callback with InputManagerService.
        inputManager.registerKeyboardSystemShortcutListener(executor, callback1)
        assertNotNull(registeredListener)

        // Adding another listener should not register new internal listener.
        val currListener = registeredListener
        inputManager.registerKeyboardSystemShortcutListener(executor, callback2)
        assertEquals(currListener, registeredListener)
    }

    @Test
    fun testRemovingListenersUnregistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.KeyboardSystemShortcutListener {_, _ -> }
        val callback2 = InputManager.KeyboardSystemShortcutListener {_, _ -> }

        inputManager.registerKeyboardSystemShortcutListener(executor, callback1)
        inputManager.registerKeyboardSystemShortcutListener(executor, callback2)

        // Only removing all listeners should remove the internal callback
        inputManager.unregisterKeyboardSystemShortcutListener(callback1)
        assertNotNull(registeredListener)
        inputManager.unregisterKeyboardSystemShortcutListener(callback2)
        assertNull(registeredListener)
    }

    @Test
    fun testMultipleListeners() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.KeyboardSystemShortcutListener { _, _ -> callbackCount1++ }
        val callback2 = InputManager.KeyboardSystemShortcutListener { _, _ -> callbackCount2++ }

        // Add both keyboard system shortcut listeners
        inputManager.registerKeyboardSystemShortcutListener(executor, callback1)
        inputManager.registerKeyboardSystemShortcutListener(executor, callback2)

        // Notifying keyboard system shortcut triggered, should notify both the callbacks.
        notifyKeyboardSystemShortcutTriggered(DEVICE_ID, HOME_SHORTCUT)
        testLooper.dispatchAll()
        assertEquals(1, callbackCount1)
        assertEquals(1, callbackCount2)

        inputManager.unregisterKeyboardSystemShortcutListener(callback2)
        // Notifying keyboard system shortcut triggered, should still trigger callback1 but not
        // callback2.
        notifyKeyboardSystemShortcutTriggered(DEVICE_ID, HOME_SHORTCUT)
        testLooper.dispatchAll()
        assertEquals(2, callbackCount1)
        assertEquals(1, callbackCount2)
    }
}
