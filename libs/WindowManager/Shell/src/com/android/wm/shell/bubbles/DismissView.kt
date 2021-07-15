/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles

import android.content.Context
import android.graphics.drawable.TransitionDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY
import androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
import com.android.wm.shell.R
import com.android.wm.shell.animation.PhysicsAnimator
import com.android.wm.shell.common.DismissCircleView

/*
 * View that handles interactions between DismissCircleView and BubbleStackView.
 */
class DismissView(context: Context) : FrameLayout(context) {

    var circle = DismissCircleView(context).apply {
        val targetSize: Int = context.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size)
        val newParams = LayoutParams(targetSize, targetSize)
        newParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        setLayoutParams(newParams)
        setTranslationY(
            resources.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height).toFloat())
    }

    var isShowing = false
    private val animator = PhysicsAnimator.getInstance(circle)
    private val spring = PhysicsAnimator.SpringConfig(STIFFNESS_LOW, DAMPING_RATIO_LOW_BOUNCY)
    private val DISMISS_SCRIM_FADE_MS = 200
    init {
        setLayoutParams(LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height),
            Gravity.BOTTOM))
        setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.floating_dismiss_bottom_margin))
        setClipToPadding(false)
        setClipChildren(false)
        setVisibility(View.INVISIBLE)
        setBackgroundResource(
            R.drawable.floating_dismiss_gradient_transition)
        addView(circle)
    }

    /**
     * Animates this view in.
     */
    fun show() {
        if (isShowing) return
        isShowing = true
        setVisibility(View.VISIBLE)
        (getBackground() as TransitionDrawable).startTransition(DISMISS_SCRIM_FADE_MS)
        animator.cancel()
        animator
            .spring(DynamicAnimation.TRANSLATION_Y, 0f, spring)
            .start()
    }

    /**
     * Animates this view out, as well as the circle that encircles the bubbles, if they
     * were dragged into the target and encircled.
     */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        (getBackground() as TransitionDrawable).reverseTransition(DISMISS_SCRIM_FADE_MS)
        animator
            .spring(DynamicAnimation.TRANSLATION_Y, height.toFloat(),
                spring)
            .withEndActions({ setVisibility(View.INVISIBLE) })
            .start()
    }

    fun updateResources() {
        val targetSize: Int = context.resources.getDimensionPixelSize(R.dimen.dismiss_circle_size)
        circle.layoutParams.width = targetSize
        circle.layoutParams.height = targetSize
        circle.requestLayout()
    }
}