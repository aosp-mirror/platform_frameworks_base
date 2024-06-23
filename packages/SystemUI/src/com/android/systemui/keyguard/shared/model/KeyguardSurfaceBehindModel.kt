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

package com.android.systemui.keyguard.shared.model

/**
 * Models the appearance of the surface behind the keyguard, and (optionally) how it should be
 * animating.
 *
 * This is intended to be an atomic, high-level description of the surface's appearance and related
 * animations, which we can derive from the STARTED/FINISHED transition states rather than the
 * individual TransitionSteps.
 *
 * For example, if we're transitioning from LOCKSCREEN to GONE, that means we should be
 * animatingFromAlpha 0f -> 1f and animatingFromTranslationY 500f -> 0f.
 * KeyguardSurfaceBehindAnimator can decide how best to implement this, depending on previously
 * running animations, spring momentum, and other state.
 */
data class KeyguardSurfaceBehindModel(
    val alpha: Float = 1f,

    /**
     * If provided, animate from this value to [alpha] unless an animation is already running, in
     * which case we'll animate from the current value to [alpha].
     */
    val animateFromAlpha: Float = alpha,
    val translationY: Float = 0f,

    /**
     * If provided, animate from this value to [translationY] unless an animation is already
     * running, in which case we'll animate from the current value to [translationY].
     */
    val animateFromTranslationY: Float = translationY,

    /** Velocity with which to start the Y-translation spring animation. */
    val startVelocity: Float = 0f,
) {
    fun willAnimateAlpha(): Boolean {
        return animateFromAlpha != alpha
    }

    fun willAnimateTranslationY(): Boolean {
        return animateFromTranslationY != translationY
    }
}
