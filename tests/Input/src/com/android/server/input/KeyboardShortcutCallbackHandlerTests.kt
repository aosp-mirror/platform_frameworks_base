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
import android.hardware.input.IKeyboardSystemShortcutListener
import android.hardware.input.KeyboardSystemShortcut
import android.platform.test.annotations.Presubmit
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit

/**
 * Tests for {@link KeyboardShortcutCallbackHandler}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardShortcutCallbackHandlerTests
 */
@Presubmit
class KeyboardShortcutCallbackHandlerTests {

    companion object {
        val DEVICE_ID = 1
        val HOME_SHORTCUT = KeyboardSystemShortcut(
            intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyboardSystemShortcut.SYSTEM_SHORTCUT_HOME
        )
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var keyboardShortcutCallbackHandler: KeyboardShortcutCallbackHandler
    private lateinit var context: Context
    private var lastShortcut: KeyboardSystemShortcut? = null

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        keyboardShortcutCallbackHandler = KeyboardShortcutCallbackHandler()
    }

    @Test
    fun testKeyboardSystemShortcutTriggered_registerUnregisterListener() {
        val listener = KeyboardSystemShortcutListener()

        // Register keyboard system shortcut listener
        keyboardShortcutCallbackHandler.registerKeyboardSystemShortcutListener(listener, 0)
        keyboardShortcutCallbackHandler.onKeyboardSystemShortcutTriggered(DEVICE_ID, HOME_SHORTCUT)
        assertEquals(
            "Listener should get callback on keyboard system shortcut triggered",
            HOME_SHORTCUT,
            lastShortcut!!
        )

        // Unregister listener
        lastShortcut = null
        keyboardShortcutCallbackHandler.unregisterKeyboardSystemShortcutListener(listener, 0)
        keyboardShortcutCallbackHandler.onKeyboardSystemShortcutTriggered(DEVICE_ID, HOME_SHORTCUT)
        assertNull("Listener should not get callback after being unregistered", lastShortcut)
    }

    inner class KeyboardSystemShortcutListener : IKeyboardSystemShortcutListener.Stub() {
        override fun onKeyboardSystemShortcutTriggered(
                deviceId: Int,
                keycodes: IntArray,
                modifierState: Int,
                shortcut: Int
        ) {
            assertEquals(DEVICE_ID, deviceId)
            lastShortcut = KeyboardSystemShortcut(keycodes, modifierState, shortcut)
        }
    }
}