/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.os.SystemClock
import android.view.ContentInfo.Source
import android.view.InputDevice.SOURCE_MOUSE
import android.view.InputDevice.SOURCE_STYLUS
import android.view.InputDevice.SOURCE_TOUCHSCREEN
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_MOUSE
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.MotionEvent.ToolType

/**
 * Helper class for injecting a custom motion event and performing some actions. This is used for
 * instrumenting input injections like stylus, mouse and touchpad.
 */
class MotionEventHelper(
    private val instr: Instrumentation,
    val inputMethod: InputMethod
) {
    enum class InputMethod(@ToolType val toolType: Int, @Source val source: Int) {
        STYLUS(TOOL_TYPE_STYLUS, SOURCE_STYLUS),
        MOUSE(TOOL_TYPE_MOUSE, SOURCE_MOUSE),
        TOUCHPAD(TOOL_TYPE_FINGER, SOURCE_MOUSE),
        TOUCH(TOOL_TYPE_FINGER, SOURCE_TOUCHSCREEN)
    }

    fun actionDown(x: Int, y: Int, time: Long = SystemClock.uptimeMillis()) {
        injectMotionEvent(ACTION_DOWN, x, y, downTime = time, eventTime = time)
    }

    fun actionUp(x: Int, y: Int, downTime: Long) {
        injectMotionEvent(ACTION_UP, x, y, downTime = downTime)
    }

    fun actionMove(startX: Int, startY: Int, endX: Int, endY: Int, steps: Int, downTime: Long) {
        val incrementX = (endX - startX).toFloat() / (steps - 1)
        val incrementY = (endY - startY).toFloat() / (steps - 1)

        for (i in 0..steps) {
            val time = SystemClock.uptimeMillis()
            val x = startX + incrementX * i
            val y = startY + incrementY * i

            val moveEvent = getMotionEvent(downTime, time, ACTION_MOVE, x, y)
            injectMotionEvent(moveEvent)
        }
    }

    private fun injectMotionEvent(
        action: Int,
        x: Int,
        y: Int,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val event = getMotionEvent(downTime, eventTime, action, x.toFloat(), y.toFloat())
        injectMotionEvent(event)
        return event
    }

    private fun injectMotionEvent(event: MotionEvent) {
        instr.uiAutomation.injectInputEvent(event, true, false)
    }

    private fun getMotionEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties.createArray(1)
        properties[0].toolType = inputMethod.toolType
        properties[0].id = 1

        val coords = MotionEvent.PointerCoords.createArray(1)
        coords[0].x = x
        coords[0].y = y
        coords[0].pressure = 1f

        val event =
            MotionEvent.obtain(
                downTime,
                eventTime,
                action,
                /* pointerCount= */ 1,
                properties,
                coords,
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision = */ 1f,
                /* yPrecision = */ 1f,
                /* deviceId = */ 0,
                /* edgeFlags = */ 0,
                inputMethod.source,
                /* flags = */ 0
            )
        event.displayId = 0
        return event
    }
}