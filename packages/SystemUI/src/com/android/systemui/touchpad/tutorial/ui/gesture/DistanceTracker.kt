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

/**
 * Tracks distance change for processed MotionEvents. Useful for recognizing gestures based on
 * distance travelled instead of specific position on the screen.
 */
class DistanceTracker(var startX: Float = 0f, var startY: Float = 0f) {
    fun processEvent(event: MotionEvent): DistanceGestureState? {
        val action = event.actionMasked
        return when (action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                Started(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> Moving(event.x - startX, event.y - startY)
            MotionEvent.ACTION_UP -> Finished(event.x - startX, event.y - startY)
            else -> null
        }
    }
}

sealed class DistanceGestureState(val deltaX: Float, val deltaY: Float)

class Started(deltaX: Float, deltaY: Float) : DistanceGestureState(deltaX, deltaY)

class Moving(deltaX: Float, deltaY: Float) : DistanceGestureState(deltaX, deltaY)

class Finished(deltaX: Float, deltaY: Float) : DistanceGestureState(deltaX, deltaY)
