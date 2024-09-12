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

interface GestureDonePredicate {
    /**
     * Should return if gesture was finished. The only events this predicate receives are ACTION_UP.
     */
    fun wasGestureDone(startX: Float, startY: Float, endX: Float, endY: Float): Boolean
}

/**
 * Common implementation for three-finger gesture monitors that are only distance-based. E.g. recent
 * apps gesture is not only distance-based because it requires going over threshold distance and
 * slowing down the movement.
 */
class ThreeFingerDistanceBasedGestureMonitor(
    override val gestureDistanceThresholdPx: Int,
    override val gestureStateChangedCallback: (GestureState) -> Unit,
    private val donePredicate: GestureDonePredicate
) : TouchpadGestureMonitor {

    private var xStart = 0f
    private var yStart = 0f

    override fun processTouchpadEvent(event: MotionEvent) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isThreeFingerTouchpadSwipe(event)) {
                    xStart = event.x
                    yStart = event.y
                    gestureStateChangedCallback(GestureState.IN_PROGRESS)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isThreeFingerTouchpadSwipe(event)) {
                    if (donePredicate.wasGestureDone(xStart, yStart, event.x, event.y)) {
                        gestureStateChangedCallback(GestureState.FINISHED)
                    } else {
                        gestureStateChangedCallback(GestureState.NOT_STARTED)
                    }
                }
            }
        }
    }
}
