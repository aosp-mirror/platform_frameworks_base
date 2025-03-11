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

package com.android.systemui.shared.clocks

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Point

class DigitTranslateAnimator(val updateCallback: () -> Unit) {
    val DEFAULT_ANIMATION_DURATION = 500L
    val updatedTranslate = Point(0, 0)

    val baseTranslation = Point(0, 0)
    var targetTranslation: Point? = null
    val bounceAnimator: ValueAnimator =
        ValueAnimator.ofFloat(1f).apply {
            duration = DEFAULT_ANIMATION_DURATION
            addUpdateListener {
                updateTranslation(it.animatedFraction, updatedTranslate)
                updateCallback()
            }
            addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        rebase()
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        rebase()
                    }
                }
            )
        }

    fun rebase() {
        baseTranslation.x = updatedTranslate.x
        baseTranslation.y = updatedTranslate.y
    }

    fun animatePosition(
        animate: Boolean = true,
        delay: Long = 0,
        duration: Long = -1L,
        interpolator: TimeInterpolator? = null,
        targetTranslation: Point? = null,
        onAnimationEnd: Runnable? = null,
    ) {
        this.targetTranslation = targetTranslation ?: Point(0, 0)
        if (animate) {
            bounceAnimator.cancel()
            bounceAnimator.startDelay = delay
            bounceAnimator.duration =
                if (duration == -1L) {
                    DEFAULT_ANIMATION_DURATION
                } else {
                    duration
                }
            interpolator?.let { bounceAnimator.interpolator = it }
            if (onAnimationEnd != null) {
                val listener =
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            onAnimationEnd.run()
                            bounceAnimator.removeListener(this)
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            bounceAnimator.removeListener(this)
                        }
                    }
                bounceAnimator.addListener(listener)
            }
            bounceAnimator.start()
        } else {
            // No animation is requested, thus set base and target state to the same state.
            updateTranslation(1F, updatedTranslate)
            rebase()
            updateCallback()
        }
    }

    fun updateTranslation(progress: Float, outPoint: Point) {
        outPoint.x =
            (baseTranslation.x + progress * (targetTranslation!!.x - baseTranslation.x)).toInt()
        outPoint.y =
            (baseTranslation.y + progress * (targetTranslation!!.y - baseTranslation.y)).toInt()
    }
}
