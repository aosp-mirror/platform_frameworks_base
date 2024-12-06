/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.view.GestureDetector.OnContextClickListener
import android.view.GestureDetector.OnDoubleTapListener
import android.view.GestureDetector.OnGestureListener
import android.view.MotionEvent

/**
 * Interface which unions all the interfaces related to gestures.
 */
interface LetterboxGestureListener : OnGestureListener, OnDoubleTapListener, OnContextClickListener

/**
 * Convenience class which provide an overrideable implementation of
 * {@link LetterboxGestureListener}.
 */
object LetterboxGestureDelegate : LetterboxGestureListener {
    override fun onDown(e: MotionEvent): Boolean = false

    override fun onShowPress(e: MotionEvent) {
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean = false

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ): Boolean = false

    override fun onLongPress(e: MotionEvent) {
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ): Boolean = false

    override fun onSingleTapConfirmed(e: MotionEvent): Boolean = false

    override fun onDoubleTap(e: MotionEvent): Boolean = false

    override fun onDoubleTapEvent(e: MotionEvent): Boolean = false

    override fun onContextClick(e: MotionEvent): Boolean = false
}
