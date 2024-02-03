/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.dream.lowlight.util

import android.view.animation.Interpolator

/**
 * Interpolator wrapper that shortens another interpolator from its original duration to a portion
 * of that duration.
 *
 * For example, an `originalDuration` of 1000 and a `newDuration` of 200 results in an animation
 * that when played for 200ms is the exact same as the first 200ms of a 1000ms animation if using
 * the original interpolator.
 *
 * This is useful for the transition between the user dream and the low light clock as some
 * animations are defined in the spec to be longer than the total duration of the animation. For
 * example, the low light clock exit translation animation is defined to last >1s while the actual
 * fade out of the low light clock is only 250ms, meaning the clock isn't visible anymore after
 * 250ms.
 *
 * Since the dream framework currently only allows one dream to be visible and running, we use this
 * interpolator to play just the first 250ms of the translation animation. Simply reducing the
 * duration of the animation would result in the text exiting much faster than intended, so a custom
 * interpolator is needed.
 */
class TruncatedInterpolator(
    private val baseInterpolator: Interpolator,
    originalDuration: Float,
    newDuration: Float
) : Interpolator {
    private val scaleFactor: Float

    init {
        scaleFactor = newDuration / originalDuration
    }

    override fun getInterpolation(input: Float): Float {
        return baseInterpolator.getInterpolation(input * scaleFactor)
    }
}
