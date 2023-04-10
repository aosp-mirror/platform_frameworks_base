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
import com.android.systemui.util.doOnCancel
import com.android.systemui.util.doOnEnd

/**
 * An implementation of [StatusBarSystemEventDefaultAnimator], applying the onAlphaChanged and
 * onTranslationXChanged callbacks directly to the provided animatedView.
 */
class StatusBarSystemEventAnimator @JvmOverloads constructor(
        val animatedView: View,
        resources: Resources,
        isAnimationRunning: Boolean = false
) : StatusBarSystemEventDefaultAnimator(
        resources = resources,
        onAlphaChanged = animatedView::setAlpha,
        onTranslationXChanged = animatedView::setTranslationX,
        isAnimationRunning = isAnimationRunning
)

/**
 * Tied directly to [SystemStatusAnimationScheduler]. Any StatusBar-like thing (keyguard, collapsed
 * status bar fragment), can use this Animator to get the default system status animation. It simply
 * needs to implement the onAlphaChanged and onTranslationXChanged callbacks.
 *
 * This animator relies on resources, and should be recreated whenever resources are updated. While
 * this class could be used directly as the animation callback, it's probably best to forward calls
 * to it so that it can be recreated at any moment without needing to remove/add callback.
 */

open class StatusBarSystemEventDefaultAnimator @JvmOverloads constructor(
        resources: Resources,
        private val onAlphaChanged: (Float) -> Unit,
        private val onTranslationXChanged: (Float) -> Unit,
        var isAnimationRunning: Boolean = false
) : SystemStatusAnimationCallback {
    private val translationXIn: Int = resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_chip_animation_in_status_bar_translation_x)
    private val translationXOut: Int = resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_chip_animation_out_status_bar_translation_x)

    override fun onSystemEventAnimationBegin(): Animator {
        isAnimationRunning = true
        val moveOut = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 23.frames
            interpolator = STATUS_BAR_X_MOVE_OUT
            addUpdateListener {
                onTranslationXChanged(-(translationXIn * animatedValue as Float))
            }
        }
        val alphaOut = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 8.frames
            interpolator = null
            addUpdateListener {
                onAlphaChanged(animatedValue as Float)
            }
        }

        val animSet = AnimatorSet()
        animSet.playTogether(moveOut, alphaOut)
        return animSet
    }

    override fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator {
        onTranslationXChanged(translationXOut.toFloat())
        val moveIn = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 23.frames
            startDelay = 7.frames
            interpolator = STATUS_BAR_X_MOVE_IN
            addUpdateListener {
                onTranslationXChanged(translationXOut * animatedValue as Float)
            }
        }
        val alphaIn = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5.frames
            startDelay = 11.frames
            interpolator = null
            addUpdateListener {
                onAlphaChanged(animatedValue as Float)
            }
        }

        val animatorSet = AnimatorSet()
        animatorSet.playTogether(moveIn, alphaIn)
        animatorSet.doOnEnd { isAnimationRunning = false }
        animatorSet.doOnCancel { isAnimationRunning = false }
        return animatorSet
    }
}