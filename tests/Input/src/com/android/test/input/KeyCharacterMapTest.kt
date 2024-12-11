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

package com.android.test.input

import android.view.KeyCharacterMap
import android.view.KeyEvent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for {@link KeyCharacterMap}.
 *
 * <p>Build/Install/Run:
 * atest KeyCharacterMapTest
 *
 */
class KeyCharacterMapTest {
    @Test
    fun testGetFallback() {
        // Based off of VIRTUAL kcm fallbacks.
        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

        // One modifier fallback.
        assertEquals(
            keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_SPACE,
                KeyEvent.META_CTRL_ON).keyCode,
            KeyEvent.KEYCODE_LANGUAGE_SWITCH)

        // Multiple modifier fallback.
        assertEquals(
            keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_DEL,
                KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON).keyCode,
            KeyEvent.KEYCODE_BACK)

        // No default button, fallback only.
        assertEquals(
            keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_BUTTON_A, 0).keyCode,
            KeyEvent.KEYCODE_DPAD_CENTER)
    }
}
