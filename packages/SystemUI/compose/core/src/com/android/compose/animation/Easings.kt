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

package com.android.compose.animation

import androidx.compose.animation.core.Easing
import androidx.core.animation.Interpolator
import com.android.app.animation.InterpolatorsAndroidX

/**
 * Compose-compatible definition of Android motion eases, see
 * https://carbon.googleplex.com/android-motion/pages/easing
 */
object Easings {

    /** The standard interpolator that should be used on every normal animation */
    val Standard = fromInterpolator(InterpolatorsAndroidX.STANDARD)

    /**
     * The standard accelerating interpolator that should be used on every regular movement of
     * content that is disappearing e.g. when moving off screen.
     */
    val StandardAccelerate = fromInterpolator(InterpolatorsAndroidX.STANDARD_ACCELERATE)

    /**
     * The standard decelerating interpolator that should be used on every regular movement of
     * content that is appearing e.g. when coming from off screen.
     */
    val StandardDecelerate = fromInterpolator(InterpolatorsAndroidX.STANDARD_DECELERATE)

    /** The default emphasized interpolator. Used for hero / emphasized movement of content. */
    val Emphasized = fromInterpolator(InterpolatorsAndroidX.EMPHASIZED)

    /**
     * The accelerated emphasized interpolator. Used for hero / emphasized movement of content that
     * is disappearing e.g. when moving off screen.
     */
    val EmphasizedAccelerate = fromInterpolator(InterpolatorsAndroidX.EMPHASIZED_ACCELERATE)

    /**
     * The decelerating emphasized interpolator. Used for hero / emphasized movement of content that
     * is appearing e.g. when coming from off screen
     */
    val EmphasizedDecelerate = fromInterpolator(InterpolatorsAndroidX.EMPHASIZED_DECELERATE)

    /** The linear interpolator. */
    val Linear = fromInterpolator(InterpolatorsAndroidX.LINEAR)

    /** The default legacy interpolator as defined in Material 1. Also known as FAST_OUT_SLOW_IN. */
    val Legacy = fromInterpolator(InterpolatorsAndroidX.LEGACY)

    /**
     * The default legacy accelerating interpolator as defined in Material 1. Also known as
     * FAST_OUT_LINEAR_IN.
     */
    val LegacyAccelerate = fromInterpolator(InterpolatorsAndroidX.LEGACY_ACCELERATE)

    /**
     * T The default legacy decelerating interpolator as defined in Material 1. Also known as
     * LINEAR_OUT_SLOW_IN.
     */
    val LegacyDecelerate = fromInterpolator(InterpolatorsAndroidX.LEGACY_DECELERATE)

    private fun fromInterpolator(source: Interpolator) = Easing { x -> source.getInterpolation(x) }
}
