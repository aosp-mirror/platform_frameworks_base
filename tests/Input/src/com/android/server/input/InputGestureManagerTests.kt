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

import android.hardware.input.InputGestureData
import android.hardware.input.InputManager
import android.hardware.input.KeyGestureEvent
import android.platform.test.annotations.Presubmit
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for custom keyboard glyph map configuration.
 *
 * Build/Install/Run:
 * atest InputTests:CustomInputGestureManagerTests
 */
@Presubmit
class InputGestureManagerTests {

    companion object {
        const val USER_ID = 1
    }

    private lateinit var inputGestureManager: InputGestureManager

    @Before
    fun setup() {
        inputGestureManager = InputGestureManager(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun addRemoveCustomGesture() {
        val customGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        val result = inputGestureManager.addCustomInputGesture(USER_ID, customGesture)
        assertEquals(InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS, result)
        assertEquals(
            listOf(customGesture),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )

        inputGestureManager.removeCustomInputGesture(USER_ID, customGesture)
        assertEquals(
            listOf<InputGestureData>(),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
    }

    @Test
    fun removeNonExistentGesture() {
        val customGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        val result = inputGestureManager.removeCustomInputGesture(USER_ID, customGesture)
        assertEquals(InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_DOES_NOT_EXIST, result)
        assertEquals(
            listOf<InputGestureData>(),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
    }

    @Test
    fun addAlreadyExistentGesture() {
        val customGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        inputGestureManager.addCustomInputGesture(USER_ID, customGesture)
        val customGesture2 = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
            .build()
        val result = inputGestureManager.addCustomInputGesture(USER_ID, customGesture2)
        assertEquals(InputManager.CUSTOM_INPUT_GESTURE_RESULT_ERROR_ALREADY_EXISTS, result)
        assertEquals(
            listOf(customGesture),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
    }

    @Test
    fun addRemoveAllExistentGestures() {
        val customGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        inputGestureManager.addCustomInputGesture(USER_ID, customGesture)
        val customGesture2 = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
            .build()
        inputGestureManager.addCustomInputGesture(USER_ID, customGesture2)

        assertEquals(
            listOf(customGesture, customGesture2),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )

        inputGestureManager.removeAllCustomInputGestures(USER_ID, /* filter = */null)
        assertEquals(
            listOf<InputGestureData>(),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
    }

    @Test
    fun filteringBasedOnTouchpadOrKeyGestures() {
        val customKeyGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createKeyTrigger(
                    KeyEvent.KEYCODE_H,
                    KeyEvent.META_META_ON
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME)
            .build()
        inputGestureManager.addCustomInputGesture(USER_ID, customKeyGesture)
        val customTouchpadGesture = InputGestureData.Builder()
            .setTrigger(
                InputGestureData.createTouchpadTrigger(
                    InputGestureData.TOUCHPAD_GESTURE_TYPE_THREE_FINGER_TAP
                )
            )
            .setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_BACK)
            .build()
        inputGestureManager.addCustomInputGesture(USER_ID, customTouchpadGesture)

        assertEquals(
            listOf(customTouchpadGesture, customKeyGesture),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
        assertEquals(
            listOf(customKeyGesture),
            inputGestureManager.getCustomInputGestures(USER_ID, InputGestureData.Filter.KEY)
        )
        assertEquals(
            listOf(customTouchpadGesture),
            inputGestureManager.getCustomInputGestures(
                USER_ID,
                InputGestureData.Filter.TOUCHPAD
            )
        )

        inputGestureManager.removeAllCustomInputGestures(USER_ID, InputGestureData.Filter.KEY)
        assertEquals(
            listOf(customTouchpadGesture),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )

        inputGestureManager.removeAllCustomInputGestures(
            USER_ID,
            InputGestureData.Filter.TOUCHPAD
        )
        assertEquals(
            listOf<InputGestureData>(),
            inputGestureManager.getCustomInputGestures(USER_ID, /* filter = */null)
        )
    }
}