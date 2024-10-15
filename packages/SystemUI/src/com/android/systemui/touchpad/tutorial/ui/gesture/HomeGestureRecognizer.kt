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

/** Recognizes touchpad home gesture, that is three fingers swiping up */
class HomeGestureRecognizer(private val gestureDistanceThresholdPx: Int) : GestureRecognizer {

    private val distanceTracker = DistanceTracker()
    private var gestureStateChangedCallback: (GestureState) -> Unit = {}

    override fun addGestureStateCallback(callback: (GestureState) -> Unit) {
        gestureStateChangedCallback = callback
    }

    override fun accept(event: MotionEvent) {
        if (!isThreeFingerTouchpadSwipe(event)) return
        val gestureState = distanceTracker.processEvent(event)
        updateGestureState(
            gestureStateChangedCallback,
            gestureState,
            isFinished = { -it.deltaY >= gestureDistanceThresholdPx },
            progress = { 0f },
        )
    }
}
