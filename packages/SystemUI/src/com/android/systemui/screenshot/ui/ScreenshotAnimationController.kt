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

package com.android.systemui.screenshot.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import com.android.systemui.res.R
import kotlin.math.abs

class ScreenshotAnimationController(private val view: ScreenshotShelfView) {
    private var animator: Animator? = null
    private val actionContainer = view.requireViewById<View>(R.id.actions_container_background)

    fun getEntranceAnimation(): Animator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.addUpdateListener { view.alpha = it.animatedFraction }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    view.alpha = 0f
                }
                override fun onAnimationEnd(animator: Animator) {
                    view.alpha = 1f
                }
            }
        )
        this.animator = animator
        return animator
    }

    fun getSwipeReturnAnimation(): Animator {
        animator?.cancel()
        val animator = ValueAnimator.ofFloat(view.translationX, 0f)
        animator.addUpdateListener { view.translationX = it.animatedValue as Float }
        this.animator = animator
        return animator
    }

    fun getSwipeDismissAnimation(velocity: Float): Animator {
        val screenWidth = view.resources.displayMetrics.widthPixels
        // translation at which point the visible UI is fully off the screen (in the direction
        // according to velocity)
        val endX =
            if (velocity < 0) {
                -1f * actionContainer.right
            } else {
                (screenWidth - actionContainer.left).toFloat()
            }
        val distance = endX - view.translationX
        val animator = ValueAnimator.ofFloat(view.translationX, endX)
        animator.addUpdateListener {
            view.translationX = it.animatedValue as Float
            view.alpha = 1f - it.animatedFraction
        }
        animator.duration = ((abs(distance / velocity))).toLong()

        this.animator = animator
        return animator
    }

    fun cancel() {
        animator?.cancel()
    }
}
