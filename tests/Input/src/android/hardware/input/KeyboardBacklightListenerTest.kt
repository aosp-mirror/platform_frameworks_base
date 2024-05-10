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

import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.HandlerExecutor
import android.os.test.TestLooper
import android.platform.test.annotations.Presubmit
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
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoJUnitRunner
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail

/**
 * Tests for [InputManager.KeyboardBacklightListener].
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardBacklightListenerTest
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class KeyboardBacklightListenerTest {
    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var testLooper: TestLooper
    private var registeredListener: IKeyboardBacklightListener? = null
    private lateinit var executor: Executor
    private lateinit var context: Context
    private lateinit var inputManager: InputManager
    private lateinit var inputManagerGlobalSession: InputManagerGlobal.TestSession

    @Mock
    private lateinit var iInputManagerMock: IInputManager

    @Before
    fun setUp() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        inputManagerGlobalSession = InputManagerGlobal.createTestSession(iInputManagerMock)
        testLooper = TestLooper()
        executor = HandlerExecutor(Handler(testLooper.looper))
        registeredListener = null
        inputManager = InputManager(context)
        `when`(context.getSystemService(Mockito.eq(Context.INPUT_SERVICE)))
                .thenReturn(inputManager)

        // Handle keyboard backlight listener registration.
        doAnswer {
            val listener = it.getArgument(0) as IKeyboardBacklightListener
            if (registeredListener != null &&
                    registeredListener!!.asBinder() != listener.asBinder()) {
                // There can only be one registered keyboard backlight listener per process.
                fail("Trying to register a new listener when one already exists")
            }
            registeredListener = listener
            null
        }.`when`(iInputManagerMock).registerKeyboardBacklightListener(any())

        // Handle keyboard backlight listener being unregistered.
        doAnswer {
            val listener = it.getArgument(0) as IKeyboardBacklightListener
            if (registeredListener == null ||
                    registeredListener!!.asBinder() != listener.asBinder()) {
                fail("Trying to unregister a listener that is not registered")
            }
            registeredListener = null
            null
        }.`when`(iInputManagerMock).unregisterKeyboardBacklightListener(any())
    }

    @After
    fun tearDown() {
        if (this::inputManagerGlobalSession.isInitialized) {
            inputManagerGlobalSession.close()
        }
    }

    private fun notifyKeyboardBacklightChanged(
        deviceId: Int,
        brightnessLevel: Int,
        maxBrightnessLevel: Int = 10,
        isTriggeredByKeyPress: Boolean = true
    ) {
        registeredListener!!.onBrightnessChanged(deviceId, IKeyboardBacklightState().apply {
            this.brightnessLevel = brightnessLevel
            this.maxBrightnessLevel = maxBrightnessLevel
        }, isTriggeredByKeyPress)
    }

    @Test
    fun testListenerIsNotifiedCorrectly() {
        var callbackCount = 0

        // Add a keyboard backlight listener
        inputManager.registerKeyboardBacklightListener(executor) {
                deviceId: Int,
                keyboardBacklightState: KeyboardBacklightState,
                isTriggeredByKeyPress: Boolean ->
            callbackCount++
            assertEquals(1, deviceId)
            assertEquals(2, keyboardBacklightState.brightnessLevel)
            assertEquals(10, keyboardBacklightState.maxBrightnessLevel)
            assertEquals(true, isTriggeredByKeyPress)
        }

        // Adding the listener should register the callback with InputManagerService.
        assertNotNull(registeredListener)

        // Notifying keyboard backlight change will notify the listener.
        notifyKeyboardBacklightChanged(1 /*deviceId*/, 2 /* brightnessLevel */)
        testLooper.dispatchNext()
        assertEquals(1, callbackCount)
    }

    @Test
    fun testMultipleListeners() {
        // Set up two callbacks.
        var callbackCount1 = 0
        var callbackCount2 = 0
        val callback1 = InputManager.KeyboardBacklightListener { _, _, _ -> callbackCount1++ }
        val callback2 = InputManager.KeyboardBacklightListener { _, _, _ -> callbackCount2++ }

        // Add both keyboard backlight listeners
        inputManager.registerKeyboardBacklightListener(executor, callback1)
        inputManager.registerKeyboardBacklightListener(executor, callback2)

        // Adding the listeners should register the callback with InputManagerService.
        assertNotNull(registeredListener)

        // Notifying keyboard backlight change trigger the both callbacks.
        notifyKeyboardBacklightChanged(1 /*deviceId*/, 1 /* brightnessLevel */)
        testLooper.dispatchAll()
        assertEquals(1, callbackCount1)
        assertEquals(1, callbackCount2)

        inputManager.unregisterKeyboardBacklightListener(callback2)
        // Notifying keyboard backlight change should still trigger callback1.
        notifyKeyboardBacklightChanged(1 /*deviceId*/, 2 /* brightnessLevel */)
        testLooper.dispatchAll()
        assertEquals(2, callbackCount1)

        // Unregister all listeners, should remove registered listener from InputManagerService
        inputManager.unregisterKeyboardBacklightListener(callback1)
        assertNull(registeredListener)
    }
}
