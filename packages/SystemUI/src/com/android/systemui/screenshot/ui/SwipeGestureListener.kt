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

package com.android.systemui.screenshot.ui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import com.android.systemui.screenshot.FloatingWindowUtil
import kotlin.math.abs

class SwipeGestureListener(
    private val view: View,
    private val onDismiss: (Float?) -> Unit,
    private val onCancel: () -> Unit
) {
    private val velocityTracker = VelocityTracker.obtain()
    private val displayMetrics = view.resources.displayMetrics

    private var startX = 0f

    fun onMotionEvent(ev: MotionEvent): Boolean {
        ev.offsetLocation(view.translationX, 0f)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker.addMovement(ev)
                startX = ev.rawX
            }
            MotionEvent.ACTION_UP -> {
                velocityTracker.computeCurrentVelocity(1)
                val xVelocity = velocityTracker.xVelocity
                if (
                    abs(xVelocity) > FloatingWindowUtil.dpToPx(displayMetrics, FLING_THRESHOLD_DP)
                ) {
                    onDismiss.invoke(xVelocity)
                    return true
                } else if (
                    abs(view.translationX) >
                        FloatingWindowUtil.dpToPx(displayMetrics, DISMISS_THRESHOLD_DP)
                ) {
                    onDismiss.invoke(xVelocity)
                    return true
                } else {
                    velocityTracker.clear()
                    onCancel.invoke()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker.addMovement(ev)
                view.translationX = ev.rawX - startX
            }
        }
        return false
    }

    companion object {
        private const val DISMISS_THRESHOLD_DP = 80f
        private const val FLING_THRESHOLD_DP = .8f // dp per ms
    }
}
