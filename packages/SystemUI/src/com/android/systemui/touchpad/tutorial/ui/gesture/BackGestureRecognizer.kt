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
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureDirection.LEFT
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureDirection.RIGHT
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.InProgress
import kotlin.math.abs

/**
 * Recognizes touchpad back gesture, that is - using three fingers on touchpad - swiping left or
 * right.
 */
class BackGestureRecognizer(private val gestureDistanceThresholdPx: Int) : GestureRecognizer {

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
        updateGestureState(
            gestureStateChangedCallback,
            gestureState,
            isFinished = { abs(it.deltaX) >= gestureDistanceThresholdPx },
            progress = ::getProgress,
        )
    }

    private fun getProgress(it: Moving): InProgress {
        val direction = if (it.deltaX > 0) RIGHT else LEFT
        val value = MathUtils.saturate(abs(it.deltaX / gestureDistanceThresholdPx))
        return InProgress(value, direction)
    }
}
