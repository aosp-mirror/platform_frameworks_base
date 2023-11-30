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

package com.android.systemui.haptics.slider

import androidx.annotation.FloatRange

/** Configuration parameters of a [SliderHapticFeedbackProvider] */
data class SliderHapticFeedbackConfig(
    /** Interpolator factor for velocity-based vibration scale interpolations. Must be positive */
    val velocityInterpolatorFactor: Float = 1f,
    /** Interpolator factor for progress-based vibration scale interpolations. Must be positive */
    val progressInterpolatorFactor: Float = 1f,
    /** Minimum vibration scale for vibrations based on slider progress */
    @FloatRange(from = 0.0, to = 1.0) val progressBasedDragMinScale: Float = 0f,
    /** Maximum vibration scale for vibrations based on slider progress */
    @FloatRange(from = 0.0, to = 1.0) val progressBasedDragMaxScale: Float = 0.2f,
    /** Additional vibration scaling due to velocity */
    @FloatRange(from = 0.0, to = 1.0) val additionalVelocityMaxBump: Float = 0.15f,
    /** Additional time delta to wait between drag texture vibrations */
    @FloatRange(from = 0.0) val deltaMillisForDragInterval: Float = 0f,
    /** Progress threshold beyond which a new drag texture is delivered */
    @FloatRange(from = 0.0, to = 1.0) val deltaProgressForDragThreshold: Float = 0.015f,
    /** Number of low ticks in a drag texture composition. This is not expected to change */
    val numberOfLowTicks: Int = 5,
    /** Maximum velocity allowed for vibration scaling. This is not expected to change. */
    val maxVelocityToScale: Float = 2000f, /* In pixels/sec */
    /** Vibration scale at the upper bookend of the slider */
    @FloatRange(from = 0.0, to = 1.0) val upperBookendScale: Float = 1f,
    /** Vibration scale at the lower bookend of the slider */
    @FloatRange(from = 0.0, to = 1.0) val lowerBookendScale: Float = 0.05f,
    /** Exponent for power function compensation */
    @FloatRange(from = 0.0, fromInclusive = false) val exponent: Float = 1f / 0.89f,
)
