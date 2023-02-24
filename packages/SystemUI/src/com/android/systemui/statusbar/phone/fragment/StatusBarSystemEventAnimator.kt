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

package com.android.systemui.statusbar.phone.fragment

import androidx.core.animation.Animator
import androidx.core.animation.AnimatorSet
import androidx.core.animation.ValueAnimator
import android.content.res.Resources
import android.view.View
import com.android.systemui.R
import com.android.systemui.statusbar.events.STATUS_BAR_X_MOVE_IN
import com.android.systemui.statusbar.events.STATUS_BAR_X_MOVE_OUT
import com.android.systemui.statusbar.events.SystemStatusAnimationCallback
import com.android.systemui.util.animation.AnimationUtil.Companion.frames

/**
 * Tied directly to [SystemStatusAnimationScheduler]. Any StatusBar-like thing (keyguard, collapsed
 * status bar fragment), can just feed this an animatable view to get the default system status
 * animation.
 *
 * This animator relies on resources, and should be recreated whenever resources are updated. While
 * this class could be used directly as the animation callback, it's probably best to forward calls
 * to it so that it can be recreated at any moment without needing to remove/add callback.
 */
class StatusBarSystemEventAnimator(
    val animatedView: View,
    resources: Resources
) : SystemStatusAnimationCallback {
    private val translationXIn: Int = resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_chip_animation_in_status_bar_translation_x)
    private val translationXOut: Int = resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_chip_animation_out_status_bar_translation_x)

    override fun onSystemEventAnimationBegin(): Animator {
        val moveOut = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 23.frames
            interpolator = STATUS_BAR_X_MOVE_OUT
            addUpdateListener {
                animatedView.translationX = -(translationXIn * animatedValue as Float)
            }
        }
        val alphaOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 8.frames
            interpolator = null
            addUpdateListener {
                animatedView.alpha = animatedValue as Float
            }
        }

        val animSet = AnimatorSet()
        animSet.playTogether(moveOut, alphaOut)
        return animSet
    }

    override fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator {
        animatedView.translationX = translationXOut.toFloat()
        val moveIn = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 23.frames
            startDelay = 7.frames
            interpolator = STATUS_BAR_X_MOVE_IN
            addUpdateListener {
                animatedView.translationX = translationXOut * animatedValue as Float
            }
        }
        val alphaIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5.frames
            startDelay = 11.frames
            interpolator = null
            addUpdateListener {
                animatedView.alpha = animatedValue as Float
            }
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(moveIn, alphaIn)

        return animatorSet
    }
}