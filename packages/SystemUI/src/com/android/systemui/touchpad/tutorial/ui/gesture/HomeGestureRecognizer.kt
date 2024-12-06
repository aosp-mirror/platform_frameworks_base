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

import android.util.MathUtils
import android.view.MotionEvent
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import kotlin.math.abs

/** Recognizes touchpad home gesture, that is - using three fingers on touchpad - swiping up. */
class HomeGestureRecognizer(
    private val gestureDistanceThresholdPx: Int,
    private val velocityThresholdPxPerMs: Float,
    private val velocityTracker: VelocityTracker = VerticalVelocityTracker(),
) : GestureRecognizer {

    private val distanceTracker = DistanceTracker()
    private var gestureStateChangedCallback: (GestureState) -> Unit = {}

    override fun addGestureStateCallback(callback: (GestureState) -> Unit) {
        gestureStateChangedCallback = callback
    }

    override fun clearGestureStateCallback() {
        gestureStateChangedCallback = {}
    }

    override fun accept(event: MotionEvent) {
        if (!isThreeFingerTouchpadSwipe(event)) return
        val gestureState = distanceTracker.processEvent(event)
        velocityTracker.accept(event)
        updateGestureState(
            gestureStateChangedCallback,
            gestureState,
            isFinished = {
                -it.deltaY >= gestureDistanceThresholdPx &&
                    abs(velocityTracker.calculateVelocity().value) >= velocityThresholdPxPerMs
            },
            progress = { InProgress(MathUtils.saturate(-it.deltaY / gestureDistanceThresholdPx)) },
        )
    }
}
