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
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import kotlin.math.abs

/**
 * Monitors recent apps gesture completion. That is - using three fingers on touchpad - swipe up
 * over some distance threshold and then slow down gesture before fingers are lifted. Implementation
 * is based on [com.android.quickstep.util.TriggerSwipeUpTouchTracker]
 */
class RecentAppsGestureMonitor(
    override val gestureDistanceThresholdPx: Int,
    override val gestureStateChangedCallback: (GestureState) -> Unit,
    private val velocityThresholdPxPerMs: Float,
    private val velocityTracker: VelocityTracker1D = VelocityTracker1D(isDataDifferential = false),
) : TouchpadGestureMonitor {

    private var xStart = 0f
    private var yStart = 0f

    override fun processTouchpadEvent(event: MotionEvent) {
        val action = event.actionMasked
        velocityTracker.addDataPoint(event.eventTime, event.y)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isThreeFingerTouchpadSwipe(event)) {
                    xStart = event.x
                    yStart = event.y
                    gestureStateChangedCallback(GestureState.IN_PROGRESS)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isThreeFingerTouchpadSwipe(event) && isRecentAppsGesture(event)) {
                    gestureStateChangedCallback(GestureState.FINISHED)
                } else {
                    gestureStateChangedCallback(GestureState.NOT_STARTED)
                }
                velocityTracker.resetTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                velocityTracker.resetTracking()
            }
        }
    }

    private fun isRecentAppsGesture(event: MotionEvent): Boolean {
        // below is trying to mirror behavior of TriggerSwipeUpTouchTracker#onGestureEnd.
        // We're diving velocity by 1000, to have the same unit of measure: pixels/ms.
        val swipeDistance = yStart - event.y
        val velocity = velocityTracker.calculateVelocity() / 1000
        return swipeDistance >= gestureDistanceThresholdPx &&
            abs(velocity) <= velocityThresholdPxPerMs
    }
}
