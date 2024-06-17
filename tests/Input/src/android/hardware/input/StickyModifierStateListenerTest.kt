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
import android.platform.test.annotations.EnableFlags
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
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for [InputManager.StickyModifierStateListener].
 *
 * Build/Install/Run:
 * atest InputTests:StickyModifierStateListenerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
@EnableFlags(
    com.android.hardware.input.Flags.FLAG_KEYBOARD_A11Y_STICKY_KEYS_FLAG,
    com.android.input.flags.Flags.FLAG_ENABLE_INPUT_FILTER_RUST_IMPL,
)
class StickyModifierStateListenerTest {

    @get:Rule
    val rule = SetFlagsRule()

    private val testLooper = TestLooper()
    private val executor = HandlerExecutor(Handler(testLooper.looper))
    private var registeredListener: IStickyModifierStateListener? = null
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

        // Handle sticky modifier state listener registration.
        doAnswer {
            val listener = it.getArgument(0) as IStickyModifierStateListener
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered sticky modifier state listener per process.
                fail("Trying to register a new listener when one already exists")
            }
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerStickyModifierStateListener(any())

        // Handle sticky modifier state listener being unregistered.
        doAnswer {
            val listener = it.getArgument(0) as IStickyModifierStateListener
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            registeredListener = null
            null
        }.`when`(iInputManagerMock).unregisterStickyModifierStateListener(any())
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun notifyStickyModifierStateChanged(modifierState: Int, lockedModifierState: Int) {
        registeredListener!!.onStickyModifierStateChanged(modifierState, lockedModifierState)
    }

    @Test
    fun testListenerIsNotifiedOnModifierStateChanged() {
        var callbackCount = 0

        // Add a sticky modifier state listener
        inputManager.registerStickyModifierStateListener(executor) {
            callbackCount++
        }

        // Notifying sticky modifier state change will notify the listener.
        notifyStickyModifierStateChanged(0, 0)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount)
    }

    @Test
    fun testListenerHasCorrectModifierStateNotified() {
        // Add a sticky modifier state listener
        inputManager.registerStickyModifierStateListener(executor) {
            state: StickyModifierState ->
            assertTrue(state.isAltModifierOn)
            assertTrue(state.isAltModifierLocked)
            assertTrue(state.isShiftModifierOn)
            assertTrue(!state.isShiftModifierLocked)
            assertTrue(!state.isCtrlModifierOn)
            assertTrue(!state.isCtrlModifierLocked)
            assertTrue(!state.isMetaModifierOn)
            assertTrue(!state.isMetaModifierLocked)
            assertTrue(!state.isAltGrModifierOn)
            assertTrue(!state.isAltGrModifierLocked)
        }

        // Notifying sticky modifier state change will notify the listener.
        notifyStickyModifierStateChanged(
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON or
                        KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON,
                KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        )
        testLooper.dispatchNext()
    }

    @Test
    fun testAddingListenersRegistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.StickyModifierStateListener {}
        val callback2 = InputManager.StickyModifierStateListener {}

        assertNull(registeredListener)

        // Adding the listener should register the callback with InputManagerService.
        inputManager.registerStickyModifierStateListener(executor, callback1)
        assertNotNull(registeredListener)

        // Adding another listener should not register new internal listener.
        val currListener = registeredListener
        inputManager.registerStickyModifierStateListener(executor, callback2)
        assertEquals(currListener, registeredListener)
    }

    @Test
    fun testRemovingListenersUnregistersInternalCallbackListener() {
        // Set up two callbacks.
        val callback1 = InputManager.StickyModifierStateListener {}
        val callback2 = InputManager.StickyModifierStateListener {}

        inputManager.registerStickyModifierStateListener(executor, callback1)
        inputManager.registerStickyModifierStateListener(executor, callback2)

        // Only removing all listeners should remove the internal callback
        inputManager.unregisterStickyModifierStateListener(callback1)
        assertNotNull(registeredListener)
        inputManager.unregisterStickyModifierStateListener(callback2)
        assertNull(registeredListener)
    }

    @Test
    fun testMultipleListeners() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.StickyModifierStateListener { _ -> callbackCount1++ }
        val callback2 = InputManager.StickyModifierStateListener { _ -> callbackCount2++ }

        // Add both sticky modifier state listeners
        inputManager.registerStickyModifierStateListener(executor, callback1)
        inputManager.registerStickyModifierStateListener(executor, callback2)

        // Notifying sticky modifier state change trigger the both callbacks.
        notifyStickyModifierStateChanged(0, 0)
        testLooper.dispatchAll()
        assertEquals(1, callbackCount1)
        assertEquals(1, callbackCount2)

        inputManager.unregisterStickyModifierStateListener(callback2)
        // Notifying sticky modifier state change should still trigger callback1 but not callback2.
        notifyStickyModifierStateChanged(0, 0)
        testLooper.dispatchAll()
        assertEquals(2, callbackCount1)
        assertEquals(1, callbackCount2)
    }
}
