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

/**
 * Returns the translation offset that should be used to avoid burn in at
 * the current time (in pixels.)
 *
 * @param amplitude Maximum translation that will be interpolated.
 * @param xAxis If we're moving on X or Y.
 */
fun getBurnInOffset(amplitude: Int, xAxis: Boolean): Int {
    return zigzag(System.currentTimeMillis() / MILLIS_PER_MINUTES,
            amplitude.toFloat(),
            if (xAxis) BURN_IN_PREVENTION_PERIOD_X else BURN_IN_PREVENTION_PERIOD_Y).toInt()
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