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
import java.util.function.Consumer

/** Velocity in pixels/ms. */
@JvmInline value class Velocity(val value: Float)

/**
 * Tracks velocity for processed MotionEvents. Useful for recognizing gestures based on velocity.
 */
interface VelocityTracker : Consumer<MotionEvent> {

    fun calculateVelocity(): Velocity
}

class VerticalVelocityTracker(
    private val velocityTracker: VelocityTracker1D = VelocityTracker1D(isDataDifferential = false)
) : VelocityTracker {

    override fun accept(event: MotionEvent) {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            velocityTracker.resetTracking()
        }
        velocityTracker.addDataPoint(event.eventTime, event.y)
    }

    /**
     * Calculates velocity on demand - this calculation can be expensive so shouldn't be called
     * after every event.
     */
    override fun calculateVelocity() = Velocity(velocityTracker.calculateVelocity() / 1000)
}
