/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.view.InputDevice.SOURCE_MOUSE
import android.view.InputDevice.SOURCE_TOUCHSCREEN
import android.view.InputEventAssigner
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Create a MotionEvent with the provided action, eventTime, and source
 */
fun createMotionEvent(action: Int, eventTime: Long, source: Int): MotionEvent {
    val downTime: Long = 10
    val x = 1f
    val y = 2f
    val pressure = 3f
    val size = 1f
    val metaState = 0
    val xPrecision = 0f
    val yPrecision = 0f
    val deviceId = 1
    val edgeFlags = 0
    val displayId = 0
    return MotionEvent.obtain(downTime, eventTime, action, x, y, pressure, size, metaState,
            xPrecision, yPrecision, deviceId, edgeFlags, source, displayId)
}

fun createKeyEvent(action: Int, eventTime: Long): KeyEvent {
    val code = KeyEvent.KEYCODE_A
    val repeat = 0
    return KeyEvent(eventTime, eventTime, action, code, repeat)
}

class InputEventAssignerTest {
    companion object {
        private const val TAG = "InputEventAssignerTest"
    }

    /**
     * A single MOVE event should be assigned to the next available frame.
     */
    @Test
    fun testTouchGesture() {
        val assigner = InputEventAssigner()
        val event = createMotionEvent(MotionEvent.ACTION_MOVE, 10, SOURCE_TOUCHSCREEN)
        val eventId = assigner.processEvent(event)
        assertEquals(event.id, eventId)
    }

    /**
     * DOWN event should be used until a vsync comes in. After vsync, the latest event should be
     * produced.
     */
    @Test
    fun testTouchDownWithMove() {
        val assigner = InputEventAssigner()
        val down = createMotionEvent(MotionEvent.ACTION_DOWN, 10, SOURCE_TOUCHSCREEN)
        val move1 = createMotionEvent(MotionEvent.ACTION_MOVE, 12, SOURCE_TOUCHSCREEN)
        val move2 = createMotionEvent(MotionEvent.ACTION_MOVE, 13, SOURCE_TOUCHSCREEN)
        val move3 = createMotionEvent(MotionEvent.ACTION_MOVE, 14, SOURCE_TOUCHSCREEN)
        val move4 = createMotionEvent(MotionEvent.ACTION_MOVE, 15, SOURCE_TOUCHSCREEN)
        var eventId = assigner.processEvent(down)
        assertEquals(down.id, eventId)
        eventId = assigner.processEvent(move1)
        assertEquals(down.id, eventId)
        eventId = assigner.processEvent(move2)
        // Even though we already had 2 move events, there was no choreographer callback yet.
        // Therefore, we should still get the id of the down event
        assertEquals(down.id, eventId)

        // Now send CALLBACK_INPUT to the assigner. It should provide the latest motion event
        assigner.onChoreographerCallback()
        eventId = assigner.processEvent(move3)
        assertEquals(move3.id, eventId)
        eventId = assigner.processEvent(move4)
        assertEquals(move4.id, eventId)
    }

    /**
     * Similar to the above test, but with SOURCE_MOUSE. Since we don't have down latency
     * concept for non-touchscreens, the latest input event will be used.
     */
    @Test
    fun testMouseDownWithMove() {
        val assigner = InputEventAssigner()
        val down = createMotionEvent(MotionEvent.ACTION_DOWN, 10, SOURCE_MOUSE)
        val move1 = createMotionEvent(MotionEvent.ACTION_MOVE, 12, SOURCE_MOUSE)
        var eventId = assigner.processEvent(down)
        assertEquals(down.id, eventId)
        eventId = assigner.processEvent(move1)
        assertEquals(move1.id, eventId)
    }

    /**
     * KeyEvents are processed immediately, so the latest event should be returned.
     */
    @Test
    fun testKeyEvent() {
        val assigner = InputEventAssigner()
        val down = createKeyEvent(KeyEvent.ACTION_DOWN, 20)
        var eventId = assigner.processEvent(down)
        assertEquals(down.id, eventId)
        val up = createKeyEvent(KeyEvent.ACTION_UP, 21)
        eventId = assigner.processEvent(up)
        // DOWN is only sticky for Motions, not for keys
        assertEquals(up.id, eventId)
        assigner.onChoreographerCallback()
        val down2 = createKeyEvent(KeyEvent.ACTION_DOWN, 22)
        eventId = assigner.processEvent(down2)
        assertEquals(down2.id, eventId)
    }
}
