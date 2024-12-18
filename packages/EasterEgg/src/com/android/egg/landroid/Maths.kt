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

package com.android.egg.landroid

import kotlin.math.exp
import kotlin.math.pow

/** smoothstep. Ken Perlin's version */
fun smooth(x: Float): Float {
    return x * x * x * (x * (x * 6 - 15) + 10)
}

/** Kind of like an inverted smoothstep, but */
fun invsmoothish(x: Float): Float {
    return 0.25f * ((2f * x - 1f).pow(5f) + 1f) + 0.5f * x
}

/** Compute the fraction that progress represents between start and end (inverse of lerp). */
fun lexp(start: Float, end: Float, progress: Float): Float {
    return (progress - start) / (end - start)
}

/** Exponentially smooth current toward target by a factor of speed. */
fun expSmooth(current: Float, target: Float, dt: Float = 1f / 60, speed: Float = 5f): Float {
    return current + (target - current) * (1 - exp(-dt * speed))
}
