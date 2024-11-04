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
import java.util.function.Consumer

/** Based on passed [MotionEvent]s recognizes different states of gesture and notifies callback. */
interface GestureRecognizer : Consumer<MotionEvent> {
    fun addGestureStateCallback(callback: (GestureState) -> Unit)

    fun clearGestureStateCallback()
}

fun isThreeFingerTouchpadSwipe(event: MotionEvent) = isNFingerTouchpadSwipe(event, fingerCount = 3)

fun isFourFingerTouchpadSwipe(event: MotionEvent) = isNFingerTouchpadSwipe(event, fingerCount = 4)

private fun isNFingerTouchpadSwipe(event: MotionEvent, fingerCount: Int): Boolean {
    return event.classification == MotionEvent.CLASSIFICATION_MULTI_FINGER_SWIPE &&
        event.getAxisValue(MotionEvent.AXIS_GESTURE_SWIPE_FINGER_COUNT) == fingerCount.toFloat()
}

fun isTwoFingerSwipe(event: MotionEvent): Boolean {
    return event.classification == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE
}
