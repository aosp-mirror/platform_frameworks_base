/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Rect
import android.transition.Transition
import android.transition.TransitionValues
import android.util.MathUtils
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import com.android.app.animation.Interpolators
import com.android.internal.R.interpolator.fast_out_extra_slow_in
import com.android.systemui.res.R

/** Animates constraint layout changes for the security view. */
class KeyguardSecurityViewTransition : Transition() {

    companion object {
        const val PROP_BOUNDS = "securityViewLocation:bounds"

        // The duration of the animation to switch security sides.
        const val SECURITY_SHIFT_ANIMATION_DURATION_MS = 500L

        // How much of the switch sides animation should be dedicated to fading the security out.
        // The remainder will fade it back in again.
        const val SECURITY_SHIFT_ANIMATION_FADE_OUT_PROPORTION = 0.2f
    }

    private fun captureValues(values: TransitionValues) {
        val boundsRect = Rect()
        boundsRect.left = values.view.left
        boundsRect.top = values.view.top
        boundsRect.right = values.view.right
        boundsRect.bottom = values.view.bottom
        values.values[PROP_BOUNDS] = boundsRect
    }

    override fun getTransitionProperties(): Array<String>? {
        return arrayOf(PROP_BOUNDS)
    }

    override fun captureEndValues(transitionValues: TransitionValues?) {
        transitionValues?.let { captureValues(it) }
    }

    override fun captureStartValues(transitionValues: TransitionValues?) {
        transitionValues?.let { captureValues(it) }
    }

    override fun createAnimator(
        sceneRoot: ViewGroup,
        startValues: TransitionValues?,
        endValues: TransitionValues?
    ): Animator? {
        if (startValues == null || endValues == null) {
            return null
        }

        // This animation is a bit fun to implement. The bouncer needs to move, and fade
        // in/out at the same time. The issue is, the bouncer should only move a short
        // amount (120dp or so), but obviously needs to go from one side of the screen to
        // the other. This needs a pretty custom animation.
        //
        // This works as follows. It uses a ValueAnimation to simply drive the animation
        // progress. This animator is responsible for both the translation of the bouncer,
        // and the current fade. It will fade the bouncer out while also moving it along the
        // 120dp path. Once the bouncer is fully faded out though, it will "snap" the
        // bouncer closer to its destination, then fade it back in again. The effect is that
        // the bouncer will move from 0 -> X while fading out, then
        // (destination - X) -> destination while fading back in again.
        // TODO(b/208250221): Make this animation properly abortable.
        val positionInterpolator =
            AnimationUtils.loadInterpolator(sceneRoot.context, fast_out_extra_slow_in)
        val fadeOutInterpolator = Interpolators.FAST_OUT_LINEAR_IN
        val fadeInInterpolator = Interpolators.LINEAR_OUT_SLOW_IN
        var runningSecurityShiftAnimator = ValueAnimator.ofFloat(0.0f, 1.0f)
        runningSecurityShiftAnimator.duration = SECURITY_SHIFT_ANIMATION_DURATION_MS
        runningSecurityShiftAnimator.interpolator = Interpolators.LINEAR
        val startRect = startValues.values[PROP_BOUNDS] as Rect
        val endRect = endValues.values[PROP_BOUNDS] as Rect
        val v = startValues.view
        val totalTranslation: Int =
            sceneRoot.resources.getDimension(R.dimen.security_shift_animation_translation).toInt()
        val shouldRestoreLayerType =
            (v.hasOverlappingRendering() && v.layerType != View.LAYER_TYPE_HARDWARE)
        if (shouldRestoreLayerType) {
            v.setLayerType(View.LAYER_TYPE_HARDWARE, /* paint= */ null)
        }
        val initialAlpha: Float = v.alpha
        runningSecurityShiftAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runningSecurityShiftAnimator = null
                    if (shouldRestoreLayerType) {
                        v.setLayerType(View.LAYER_TYPE_NONE, /* paint= */ null)
                    }
                }
            }
        )

        runningSecurityShiftAnimator.addUpdateListener { animation: ValueAnimator ->
            val switchPoint = SECURITY_SHIFT_ANIMATION_FADE_OUT_PROPORTION
            val isFadingOut = animation.animatedFraction < switchPoint
            val opacity: Float
            var currentTranslation =
                (positionInterpolator.getInterpolation(animation.animatedFraction) *
                        totalTranslation)
                    .toInt()
            var translationRemaining = totalTranslation - currentTranslation
            val leftAlign = endRect.left < startRect.left
            if (leftAlign) {
                currentTranslation = -currentTranslation
                translationRemaining = -translationRemaining
            }

            if (isFadingOut) {
                // The bouncer fades out over the first X%.
                val fadeOutFraction =
                    MathUtils.constrainedMap(
                        /* rangeMin= */ 1.0f,
                        /* rangeMax= */ 0.0f,
                        /* valueMin= */ 0.0f,
                        /* valueMax= */ switchPoint,
                        animation.animatedFraction
                    )
                opacity = fadeOutInterpolator.getInterpolation(fadeOutFraction)

                // When fading out, the alpha needs to start from the initial opacity of the
                // view flipper, otherwise we get a weird bit of jank as it ramps back to
                // 100%.
                v.alpha = opacity * initialAlpha
                if (v is KeyguardSecurityViewFlipper) {
                    v.setLeftTopRightBottom(
                        startRect.left + currentTranslation,
                        startRect.top,
                        startRect.right + currentTranslation,
                        startRect.bottom
                    )
                } else {
                    v.setLeftTopRightBottom(
                        startRect.left,
                        startRect.top,
                        startRect.right,
                        startRect.bottom
                    )
                }
            } else {
                // And in again over the remaining (100-X)%.
                val fadeInFraction =
                    MathUtils.constrainedMap(
                        /* rangeMin= */ 0.0f,
                        /* rangeMax= */ 1.0f,
                        /* valueMin= */ switchPoint,
                        /* valueMax= */ 1.0f,
                        animation.animatedFraction
                    )
                opacity = fadeInInterpolator.getInterpolation(fadeInFraction)
                v.alpha = opacity

                // Fading back in, animate towards the destination.
                if (v is KeyguardSecurityViewFlipper) {
                    v.setLeftTopRightBottom(
                        endRect.left - translationRemaining,
                        endRect.top,
                        endRect.right - translationRemaining,
                        endRect.bottom
                    )
                } else {
                    v.setLeftTopRightBottom(
                        endRect.left,
                        endRect.top,
                        endRect.right,
                        endRect.bottom
                    )
                }
            }
        }
        runningSecurityShiftAnimator.start()
        return runningSecurityShiftAnimator
    }
}
