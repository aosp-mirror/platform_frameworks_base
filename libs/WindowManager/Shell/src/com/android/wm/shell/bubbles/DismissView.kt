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

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.IntProperty
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
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

    var circle = DismissCircleView(context)
    var isShowing = false
    var targetSizeResId: Int

    private val animator = PhysicsAnimator.getInstance(circle)
    private val spring = PhysicsAnimator.SpringConfig(STIFFNESS_LOW, DAMPING_RATIO_LOW_BOUNCY)
    private val DISMISS_SCRIM_FADE_MS = 200L
    private var wm: WindowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var gradientDrawable = createGradient()

    private val GRADIENT_ALPHA: IntProperty<GradientDrawable> =
            object : IntProperty<GradientDrawable>("alpha") {
        override fun setValue(d: GradientDrawable, percent: Int) {
            d.alpha = percent
        }
        override fun get(d: GradientDrawable): Int {
            return d.alpha
        }
    }

    init {
        setLayoutParams(LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            resources.getDimensionPixelSize(R.dimen.floating_dismiss_gradient_height),
            Gravity.BOTTOM))
        updatePadding()
        setClipToPadding(false)
        setClipChildren(false)
        setVisibility(View.INVISIBLE)
        setBackgroundDrawable(gradientDrawable)

        targetSizeResId = R.dimen.dismiss_circle_size
        val targetSize: Int = resources.getDimensionPixelSize(targetSizeResId)
        addView(circle, LayoutParams(targetSize, targetSize,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        // start with circle offscreen so it's animated up
        circle.setTranslationY(resources.getDimensionPixelSize(
                R.dimen.floating_dismiss_gradient_height).toFloat())
    }

    /**
     * Animates this view in.
     */
    fun show() {
        if (isShowing) return
        isShowing = true
        setVisibility(View.VISIBLE)
        val alphaAnim = ObjectAnimator.ofInt(gradientDrawable, GRADIENT_ALPHA,
                gradientDrawable.alpha, 255)
        alphaAnim.setDuration(DISMISS_SCRIM_FADE_MS)
        alphaAnim.start()

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
        val alphaAnim = ObjectAnimator.ofInt(gradientDrawable, GRADIENT_ALPHA,
                gradientDrawable.alpha, 0)
        alphaAnim.setDuration(DISMISS_SCRIM_FADE_MS)
        alphaAnim.start()
        animator
            .spring(DynamicAnimation.TRANSLATION_Y, height.toFloat(),
                spring)
            .withEndActions({ setVisibility(View.INVISIBLE) })
            .start()
    }

    /**
     * Cancels the animator for the dismiss target.
     */
    fun cancelAnimators() {
        animator.cancel()
    }

    fun updateResources() {
        updatePadding()
        layoutParams.height = resources.getDimensionPixelSize(
                R.dimen.floating_dismiss_gradient_height)

        val targetSize = resources.getDimensionPixelSize(targetSizeResId)
        circle.layoutParams.width = targetSize
        circle.layoutParams.height = targetSize
        circle.requestLayout()
    }

    private fun createGradient(): GradientDrawable {
        val gradientColor = context.resources.getColor(android.R.color.system_neutral1_900)
        val alpha = 0.7f * 255
        val gradientColorWithAlpha = Color.argb(alpha.toInt(),
                Color.red(gradientColor),
                Color.green(gradientColor),
                Color.blue(gradientColor))
        val gd = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(gradientColorWithAlpha, Color.TRANSPARENT))
        gd.setAlpha(0)
        return gd
    }

    private fun updatePadding() {
        val insets: WindowInsets = wm.getCurrentWindowMetrics().getWindowInsets()
        val navInset = insets.getInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars())
        setPadding(0, 0, 0, navInset.bottom +
                resources.getDimensionPixelSize(R.dimen.floating_dismiss_bottom_margin))
    }
}
