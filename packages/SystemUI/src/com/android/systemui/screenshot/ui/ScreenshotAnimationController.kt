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

class ScreenshotAnimationController(private val view: View) {
    private var animator: Animator? = null

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

    fun getExitAnimation(): Animator {
        val animator = ValueAnimator.ofFloat(1f, 0f)
        animator.addUpdateListener { view.alpha = it.animatedValue as Float }
        animator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animator: Animator) {
                    view.alpha = 1f
                }
                override fun onAnimationEnd(animator: Animator) {
                    view.alpha = 0f
                }
            }
        )
        this.animator = animator
        return animator
    }

    fun cancel() {
        animator?.cancel()
    }
}
