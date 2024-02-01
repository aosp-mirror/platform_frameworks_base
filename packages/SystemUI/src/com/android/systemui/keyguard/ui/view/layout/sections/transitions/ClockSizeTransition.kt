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
import android.animation.ObjectAnimator
import android.transition.ChangeBounds
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.transition.Visibility
import android.view.View
import android.view.ViewGroup
import com.android.app.animation.Interpolators
import com.android.systemui.keyguard.ui.view.layout.blueprints.transitions.IntraBlueprintTransitionType
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR

const val CLOCK_OUT_MILLIS = 133L
const val CLOCK_IN_MILLIS = 167L
val CLOCK_IN_INTERPOLATOR = Interpolators.LINEAR_OUT_SLOW_IN
const val CLOCK_IN_START_DELAY_MILLIS = 133L
val CLOCK_OUT_INTERPOLATOR = Interpolators.LINEAR

class ClockSizeTransition(
    val type: IntraBlueprintTransitionType,
    clockViewModel: KeyguardClockViewModel
) : TransitionSet() {
    init {
        ordering = ORDERING_TOGETHER
        addTransition(ClockOutTransition(clockViewModel, type))
        addTransition(ClockInTransition(clockViewModel, type))
        addTransition(SmartspaceChangeBounds(clockViewModel, type))
        addTransition(ClockInChangeBounds(clockViewModel, type))
        addTransition(ClockOutChangeBounds(clockViewModel, type))
    }

    class ClockInTransition(viewModel: KeyguardClockViewModel, type: IntraBlueprintTransitionType) :
        Visibility() {
        init {
            mode = MODE_IN
            if (type != IntraBlueprintTransitionType.NoTransition) {
                duration = CLOCK_IN_MILLIS
                startDelay = CLOCK_IN_START_DELAY_MILLIS
                interpolator = Interpolators.LINEAR_OUT_SLOW_IN
            } else {
                duration = 0
                startDelay = 0
            }

            addTarget(sharedR.id.bc_smartspace_view)
            addTarget(sharedR.id.date_smartspace_view)
            addTarget(sharedR.id.weather_smartspace_view)
            if (viewModel.useLargeClock) {
                viewModel.clock?.let { it.largeClock.layout.views.forEach { addTarget(it) } }
            } else {
                addTarget(R.id.lockscreen_clock_view)
            }
        }

        override fun onAppear(
            sceneRoot: ViewGroup?,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator {
            return ObjectAnimator.ofFloat(view, "alpha", 1f).also {
                it.duration = duration
                it.startDelay = startDelay
                it.interpolator = interpolator
                it.addUpdateListener { view.alpha = it.animatedValue as Float }
                it.start()
            }
        }
    }

    class ClockOutTransition(
        viewModel: KeyguardClockViewModel,
        type: IntraBlueprintTransitionType
    ) : Visibility() {
        init {
            mode = MODE_OUT
            if (type != IntraBlueprintTransitionType.NoTransition) {
                duration = CLOCK_OUT_MILLIS
                interpolator = CLOCK_OUT_INTERPOLATOR
            } else {
                duration = 0
            }

            addTarget(sharedR.id.bc_smartspace_view)
            addTarget(sharedR.id.date_smartspace_view)
            addTarget(sharedR.id.weather_smartspace_view)
            if (viewModel.useLargeClock) {
                addTarget(R.id.lockscreen_clock_view)
            } else {
                viewModel.clock?.let { it.largeClock.layout.views.forEach { addTarget(it) } }
            }
        }

        override fun onDisappear(
            sceneRoot: ViewGroup?,
            view: View,
            startValues: TransitionValues?,
            endValues: TransitionValues?
        ): Animator {
            return ObjectAnimator.ofFloat(view, "alpha", 0f).also {
                it.duration = duration
                it.interpolator = interpolator
                it.addUpdateListener { view.alpha = it.animatedValue as Float }
                it.start()
            }
        }
    }

    class ClockInChangeBounds(
        viewModel: KeyguardClockViewModel,
        type: IntraBlueprintTransitionType
    ) : ChangeBounds() {
        init {
            if (type != IntraBlueprintTransitionType.NoTransition) {
                duration = CLOCK_IN_MILLIS
                startDelay = CLOCK_IN_START_DELAY_MILLIS
                interpolator = CLOCK_IN_INTERPOLATOR
            } else {
                duration = 0
                startDelay = 0
            }

            if (viewModel.useLargeClock) {
                viewModel.clock?.let { it.largeClock.layout.views.forEach { addTarget(it) } }
            } else {
                addTarget(R.id.lockscreen_clock_view)
            }
        }
    }

    class ClockOutChangeBounds(
        viewModel: KeyguardClockViewModel,
        type: IntraBlueprintTransitionType
    ) : ChangeBounds() {
        init {
            if (type != IntraBlueprintTransitionType.NoTransition) {
                duration = CLOCK_OUT_MILLIS
                interpolator = CLOCK_OUT_INTERPOLATOR
            } else {
                duration = 0
            }
            if (viewModel.useLargeClock) {
                addTarget(R.id.lockscreen_clock_view)
            } else {
                viewModel.clock?.let { it.largeClock.layout.views.forEach { addTarget(it) } }
            }
        }
    }

    class SmartspaceChangeBounds(
        viewModel: KeyguardClockViewModel,
        val type: IntraBlueprintTransitionType = IntraBlueprintTransitionType.DefaultTransition
    ) : ChangeBounds() {
        init {
            if (type != IntraBlueprintTransitionType.NoTransition) {
                duration =
                    if (viewModel.useLargeClock) {
                        STATUS_AREA_MOVE_UP_MILLIS
                    } else {
                        STATUS_AREA_MOVE_DOWN_MILLIS
                    }
                interpolator = Interpolators.EMPHASIZED
            } else {
                duration = 0
            }
            addTarget(sharedR.id.date_smartspace_view)
            addTarget(sharedR.id.weather_smartspace_view)
            addTarget(sharedR.id.bc_smartspace_view)
        }

        companion object {
            const val STATUS_AREA_MOVE_UP_MILLIS = 967L
            const val STATUS_AREA_MOVE_DOWN_MILLIS = 467L
        }
    }
}
