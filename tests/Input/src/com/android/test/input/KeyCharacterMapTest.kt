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

import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule

import android.view.KeyCharacterMap
import android.view.KeyEvent

import com.android.hardware.input.Flags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/**
 * Tests for {@link KeyCharacterMap}.
 *
 * <p>Build/Install/Run:
 * atest KeyCharacterMapTest
 *
 */
class KeyCharacterMapTest {
    @get:Rule
    val setFlagsRule = SetFlagsRule()

    @Test
    @EnableFlags(Flags.FLAG_REMOVE_FALLBACK_MODIFIERS)
    fun testGetFallback() {
        // Based off of VIRTUAL kcm fallbacks.
        val keyCharacterMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)

        // One modifier fallback.
        val oneModifierFallback = keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_CTRL_ON)
        assertEquals(KeyEvent.KEYCODE_LANGUAGE_SWITCH, oneModifierFallback.keyCode)
        assertEquals(0, oneModifierFallback.metaState)

        // Multiple modifier fallback.
        val twoModifierFallback = keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_DEL,
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertEquals(KeyEvent.KEYCODE_BACK, twoModifierFallback.keyCode)
        assertEquals(0, twoModifierFallback.metaState)

        // No default button, fallback only.
        val keyOnlyFallback =
            keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_BUTTON_A, 0)
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, keyOnlyFallback.keyCode)
        assertEquals(0, keyOnlyFallback.metaState)

        // A key event that is not an exact match for a fallback. Expect a null return.
        // E.g. Ctrl + Space -> LanguageSwitch
        //      Ctrl + Alt + Space -> Ctrl + Alt + Space (No fallback).
        val noMatchFallback = keyCharacterMap.getFallbackAction(KeyEvent.KEYCODE_SPACE,
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)
        assertNull(noMatchFallback)
    }
}
