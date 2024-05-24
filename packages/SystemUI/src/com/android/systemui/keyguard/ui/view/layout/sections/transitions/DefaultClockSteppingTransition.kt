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

package com.android.systemui.keyguard.ui.view.layout.sections.transitions

import android.animation.Animator
import android.animation.ValueAnimator
import android.transition.Transition
import android.transition.TransitionValues
import android.view.ViewGroup
import com.android.app.animation.Interpolators
import com.android.systemui.plugins.clocks.ClockController

class DefaultClockSteppingTransition(
    private val clock: ClockController,
) : Transition() {
    init {
        interpolator = Interpolators.LINEAR
        duration = KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION_MS
        addTarget(clock.largeClock.view)
    }

    private fun captureValues(transitionValues: TransitionValues) {
        transitionValues.values[PROP_BOUNDS_LEFT] = transitionValues.view.left
        val locationInWindowTmp = IntArray(2)
        transitionValues.view.getLocationInWindow(locationInWindowTmp)
        transitionValues.values[PROP_X_IN_WINDOW] = locationInWindowTmp[0]
    }

    override fun captureEndValues(transitionValues: TransitionValues) {
        captureValues(transitionValues)
    }

    override fun captureStartValues(transitionValues: TransitionValues) {
        captureValues(transitionValues)
    }

    override fun createAnimator(
        sceneRoot: ViewGroup,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        if (startValues == null || endValues == null) {
            return null
        }
        val anim = ValueAnimator.ofFloat(0f, 1f)
        val fromLeft = startValues.values[PROP_BOUNDS_LEFT] as Int
        val fromWindowX = startValues.values[PROP_X_IN_WINDOW] as Int
        val toWindowX = endValues.values[PROP_X_IN_WINDOW] as Int
        // Using windowX, to determine direction, instead of left, as in RTL the difference of
        // toLeft - fromLeft is always positive, even when moving left.
        val direction = if (toWindowX - fromWindowX > 0) 1 else -1
        anim.addUpdateListener { animation: ValueAnimator ->
            clock.largeClock.animations.onPositionUpdated(
                fromLeft,
                direction,
                animation.animatedFraction
            )
        }
        return anim
    }

    override fun getTransitionProperties(): Array<String> {
        return TRANSITION_PROPERTIES
    }

    companion object {
        private const val PROP_BOUNDS_LEFT = "DefaultClockSteppingTransition:boundsLeft"
        private const val PROP_X_IN_WINDOW = "DefaultClockSteppingTransition:xInWindow"
        private val TRANSITION_PROPERTIES = arrayOf(PROP_BOUNDS_LEFT, PROP_X_IN_WINDOW)
        private const val KEYGUARD_STATUS_VIEW_CUSTOM_CLOCK_MOVE_DURATION_MS = 1000L
    }
}
