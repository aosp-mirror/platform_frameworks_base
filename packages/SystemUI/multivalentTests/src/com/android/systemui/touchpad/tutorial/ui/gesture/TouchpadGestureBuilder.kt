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

package com.android.systemui.touchpad.tutorial.ui.gesture

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.DEFAULT_X
import com.android.systemui.touchpad.tutorial.ui.gesture.MultiFingerGesture.Companion.DEFAULT_Y

/**
 * Interface for gesture builders which support creating list of [MotionEvent] for common swipe
 * gestures. For simple usage see swipe* methods or use [createEvents] for more specific scenarios.
 */
interface MultiFingerGesture {

    companion object {
        const val SWIPE_DISTANCE = 100f
        const val DEFAULT_X = 500f
        const val DEFAULT_Y = 500f
    }

    fun swipeUp(distancePx: Float = SWIPE_DISTANCE) = createEvents { move(deltaY = -distancePx) }

    fun swipeDown(distancePx: Float = SWIPE_DISTANCE) = createEvents { move(deltaY = distancePx) }

    fun swipeRight(distancePx: Float = SWIPE_DISTANCE) = createEvents { move(deltaX = distancePx) }

    fun swipeLeft(distancePx: Float = SWIPE_DISTANCE) = createEvents { move(deltaX = -distancePx) }

    /**
     * Creates gesture with provided move events. Note that move event's x and y is always relative
     * to the starting one
     */
    fun createEvents(moveEvents: GestureBuilder.() -> Unit): List<MotionEvent>
}

object ThreeFingerGesture : MultiFingerGesture {
    override fun createEvents(moveEvents: GestureBuilder.() -> Unit): List<MotionEvent> {
        return touchpadGesture(
            startEvents = { x, y -> startEvents(x, y) },
            moveEvents = GestureBuilder(::threeFingerEvent).apply { moveEvents() }.events,
            endEvents = { x, y -> endEvents(x, y) }
        )
    }

    fun startEvents(x: Float, y: Float): List<MotionEvent> {
        return listOf(
            threeFingerEvent(ACTION_DOWN, x, y),
            threeFingerEvent(ACTION_POINTER_DOWN, x, y),
            threeFingerEvent(ACTION_POINTER_DOWN, x, y)
        )
    }

    private fun endEvents(x: Float, y: Float): List<MotionEvent> {
        return listOf(
            threeFingerEvent(ACTION_POINTER_UP, x, y),
            threeFingerEvent(ACTION_POINTER_UP, x, y),
            threeFingerEvent(ACTION_UP, x, y)
        )
    }

    private fun threeFingerEvent(
        action: Int,
        x: Float = DEFAULT_X,
        y: Float = DEFAULT_Y
    ): MotionEvent {
        return touchpadEvent(
            action = action,
            x = x,
            y = y,
            classification = MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE,
            axisValues = mapOf(MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT to 3f)
        )
    }
}

object FourFingerGesture : MultiFingerGesture {

    override fun createEvents(moveEvents: GestureBuilder.() -> Unit): List<MotionEvent> {
        return touchpadGesture(
            startEvents = { x, y -> startEvents(x, y) },
            moveEvents = GestureBuilder(::fourFingerEvent).apply { moveEvents() }.events,
            endEvents = { x, y -> endEvents(x, y) }
        )
    }

    private fun startEvents(x: Float, y: Float): List<MotionEvent> {
        return listOf(
            fourFingerEvent(ACTION_DOWN, x, y),
            fourFingerEvent(ACTION_POINTER_DOWN, x, y),
            fourFingerEvent(ACTION_POINTER_DOWN, x, y),
            fourFingerEvent(ACTION_POINTER_DOWN, x, y)
        )
    }

    private fun endEvents(x: Float, y: Float): List<MotionEvent> {
        return listOf(
            fourFingerEvent(ACTION_POINTER_UP, x, y),
            fourFingerEvent(ACTION_POINTER_UP, x, y),
            fourFingerEvent(ACTION_POINTER_UP, x, y),
            fourFingerEvent(ACTION_UP, x, y)
        )
    }

    private fun fourFingerEvent(
        action: Int,
        x: Float = DEFAULT_X,
        y: Float = DEFAULT_Y
    ): MotionEvent {
        return touchpadEvent(
            action = action,
            x = x,
            y = y,
            classification = MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE,
            axisValues = mapOf(MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT to 4f)
        )
    }
}

object TwoFingerGesture : MultiFingerGesture {

    override fun createEvents(moveEvents: GestureBuilder.() -> Unit): List<MotionEvent> {
        return touchpadGesture(
            startEvents = { x, y -> listOf(twoFingerEvent(ACTION_DOWN, x, y)) },
            moveEvents = GestureBuilder(::twoFingerEvent).apply { moveEvents() }.events,
            endEvents = { x, y -> listOf(twoFingerEvent(ACTION_UP, x, y)) }
        )
    }

    private fun twoFingerEvent(
        action: Int,
        x: Float = DEFAULT_X,
        y: Float = DEFAULT_Y
    ): MotionEvent {
        return touchpadEvent(
            action = action,
            x = x,
            y = y,
            classification = MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE,
            axisValues = mapOf(MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT to 2f)
        )
    }
}

private fun touchpadGesture(
    startEvents: (Float, Float) -> List<MotionEvent>,
    moveEvents: List<MotionEvent>,
    endEvents: (Float, Float) -> List<MotionEvent>
): List<MotionEvent> {
    val lastX = moveEvents.last().x
    val lastY = moveEvents.last().y
    return startEvents(DEFAULT_X, DEFAULT_Y) + moveEvents + endEvents(lastX, lastY)
}

class GestureBuilder internal constructor(val eventBuilder: (Int, Float, Float) -> MotionEvent) {

    val events = mutableListOf<MotionEvent>()

    fun move(deltaX: Float = 0f, deltaY: Float = 0f) {
        events.add(eventBuilder(ACTION_MOVE, DEFAULT_X + deltaX, DEFAULT_Y + deltaY))
    }
}
