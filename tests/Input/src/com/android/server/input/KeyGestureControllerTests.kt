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
import android.hardware.input.IKeyGestureEventListener
import android.hardware.input.KeyGestureEvent
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
 * Tests for {@link KeyGestureController}.
 *
 * Build/Install/Run:
 * atest InputTests:KeyGestureControllerTests
 */
@Presubmit
class KeyGestureControllerTests {

    companion object {
        val DEVICE_ID = 1
        val HOME_GESTURE_EVENT = KeyGestureEvent(
            DEVICE_ID,
            intArrayOf(KeyEvent.KEYCODE_H),
            KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON,
            KeyGestureEvent.KEY_GESTURE_TYPE_HOME
        )
    }

    @get:Rule
    val rule = MockitoJUnit.rule()!!

    private lateinit var keyGestureController: KeyGestureController
    private lateinit var context: Context
    private var lastEvent: KeyGestureEvent? = null

    @Before
    fun setup() {
        context = Mockito.spy(ContextWrapper(ApplicationProvider.getApplicationContext()))
        keyGestureController = KeyGestureController()
    }

    @Test
    fun testKeyGestureEvent_registerUnregisterListener() {
        val listener = KeyGestureEventListener()

        // Register key gesture event listener
        keyGestureController.registerKeyGestureEventListener(listener, 0)
        keyGestureController.onKeyGestureEvent(HOME_GESTURE_EVENT)
        assertEquals(
            "Listener should get callback on key gesture event",
            HOME_GESTURE_EVENT,
            lastEvent!!
        )

        // Unregister listener
        lastEvent = null
        keyGestureController.unregisterKeyGestureEventListener(listener, 0)
        keyGestureController.onKeyGestureEvent(HOME_GESTURE_EVENT)
        assertNull("Listener should not get callback after being unregistered", lastEvent)
    }

    inner class KeyGestureEventListener : IKeyGestureEventListener.Stub() {
        override fun onKeyGestureEvent(
                deviceId: Int,
                keycodes: IntArray,
                modifierState: Int,
                gestureType: Int
        ) {
            lastEvent = KeyGestureEvent(deviceId, keycodes, modifierState, gestureType)
        }
    }
}