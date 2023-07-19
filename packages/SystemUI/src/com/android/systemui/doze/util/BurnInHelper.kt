/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze.util

import android.util.MathUtils

private const val MILLIS_PER_MINUTES = 1000 * 60f
private const val BURN_IN_PREVENTION_PERIOD_Y = 521f
private const val BURN_IN_PREVENTION_PERIOD_X = 83f
private const val BURN_IN_PREVENTION_PERIOD_SCALE = 181f
private const val BURN_IN_PREVENTION_PERIOD_PROGRESS = 89f

/**
 * Returns the translation offset that should be used to avoid burn in at
 * the current time (in pixels.)
 *
 * @param amplitude Maximum translation that will be interpolated.
 * @param xAxis If we're moving on X or Y.
 */
@JvmOverloads
fun getBurnInOffset(
    amplitude: Int,
    xAxis: Boolean,
    periodX: Float = BURN_IN_PREVENTION_PERIOD_X,
    periodY: Float = BURN_IN_PREVENTION_PERIOD_Y
): Int {
    return zigzag(
        System.currentTimeMillis() / MILLIS_PER_MINUTES,
        amplitude.toFloat(),
        if (xAxis) periodX else periodY
    ).toInt()
}

/**
 * Returns a progress offset (between 0f and 1.0f) that should be used to avoid burn in at
 * the current time.
 */
fun getBurnInProgressOffset(): Float {
    return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
        1f, BURN_IN_PREVENTION_PERIOD_PROGRESS)
}

/**
 * Returns a value to scale a view in order to avoid burn in.
 */
fun getBurnInScale(): Float {
    return 0.8f + zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
            0.2f, BURN_IN_PREVENTION_PERIOD_SCALE)
}

/**
 * Implements a continuous, piecewise linear, periodic zig-zag function
 *
 * Can be thought of as a linear approximation of abs(sin(x)))
 *
 * @param period period of the function, ie. zigzag(x + period) == zigzag(x)
 * @param amplitude maximum value of the function
 * @return a value between 0 and amplitude
 */
private fun zigzag(x: Float, amplitude: Float, period: Float): Float {
    val xprime = x % period / (period / 2)
    val interpolationAmount = if (xprime <= 1) xprime else 2 - xprime
    return MathUtils.lerp(0f, amplitude, interpolationAmount)
}