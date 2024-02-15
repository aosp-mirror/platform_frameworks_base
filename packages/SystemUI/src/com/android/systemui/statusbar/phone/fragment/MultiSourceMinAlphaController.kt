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

package com.android.systemui.statusbar.phone.fragment

import android.view.View
import androidx.core.animation.Interpolator
import androidx.core.animation.ValueAnimator
import com.android.app.animation.InterpolatorsAndroidX

/**
 * A controller that keeps track of multiple sources applying alpha value changes to a view. It will
 * always apply the minimum alpha value of all sources.
 */
internal class MultiSourceMinAlphaController
@JvmOverloads
constructor(private val view: View, private val initialAlpha: Float = 1f) {

    private val alphas = mutableMapOf<Int, Float>()
    private val animators = mutableMapOf<Int, ValueAnimator>()

    /**
     * Sets the alpha of the provided source and applies it to the view (if no other source has set
     * a lower alpha currently). If an animator of the same source is still running (i.e.
     * [animateToAlpha] was called before), that animator is cancelled.
     */
    fun setAlpha(alpha: Float, sourceId: Int) {
        animators[sourceId]?.cancel()
        updateAlpha(alpha, sourceId)
    }

    /** Animates to the alpha of the provided source. */
    fun animateToAlpha(
        alpha: Float,
        sourceId: Int,
        duration: Long,
        interpolator: Interpolator = InterpolatorsAndroidX.ALPHA_IN,
        startDelay: Long = 0
    ) {
        animators[sourceId]?.cancel()
        val animator = ValueAnimator.ofFloat(getMinAlpha(), alpha)
        animator.duration = duration
        animator.startDelay = startDelay
        animator.interpolator = interpolator
        animator.addUpdateListener { updateAlpha(animator.animatedValue as Float, sourceId) }
        animator.start()
        animators[sourceId] = animator
    }

    fun reset() {
        alphas.clear()
        animators.forEach { it.value.cancel() }
        animators.clear()
        applyAlphaToView()
    }

    private fun updateAlpha(alpha: Float, sourceId: Int) {
        alphas[sourceId] = alpha
        applyAlphaToView()
    }

    private fun applyAlphaToView() {
        val minAlpha = getMinAlpha()
        view.visibility = if (minAlpha != 0f) View.VISIBLE else View.INVISIBLE
        view.alpha = minAlpha
    }

    private fun getMinAlpha() = alphas.minOfOrNull { it.value } ?: initialAlpha
}
