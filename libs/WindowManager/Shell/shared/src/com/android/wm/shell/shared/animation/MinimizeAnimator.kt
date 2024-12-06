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

package com.android.wm.shell.shared.animation

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.util.DisplayMetrics
import android.view.SurfaceControl.Transaction
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.window.TransitionInfo.Change

/** Creates minimization animation */
object MinimizeAnimator {

    private const val MINIMIZE_ANIM_ALPHA_DURATION_MS = 100L

    private val STANDARD_ACCELERATE = PathInterpolator(0.3f, 0f, 1f, 1f)

    private val minimizeBoundsAnimationDef =
        WindowAnimator.BoundsAnimationParams(
            durationMs = 200,
            endOffsetYDp = 12f,
            endScale = 0.97f,
            interpolator = STANDARD_ACCELERATE,
        )

    @JvmStatic
    fun create(
        displayMetrics: DisplayMetrics,
        change: Change,
        transaction: Transaction,
        onAnimFinish: (Animator) -> Unit,
    ): Animator {
        val boundsAnimator = WindowAnimator.createBoundsAnimator(
            displayMetrics,
            minimizeBoundsAnimationDef,
            change,
            transaction,
        )
        val alphaAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = MINIMIZE_ANIM_ALPHA_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                transaction.setAlpha(change.leash, animation.animatedValue as Float).apply()
            }
        }
        val listener = object : Animator.AnimatorListener {
            override fun onAnimationEnd(animator: Animator) = onAnimFinish(animator)
            override fun onAnimationCancel(animator: Animator) = Unit
            override fun onAnimationRepeat(animator: Animator) = Unit
            override fun onAnimationStart(animator: Animator) = Unit
        }
        return AnimatorSet().apply {
            playTogether(boundsAnimator, alphaAnimator)
            addListener(listener)
        }
    }
}
