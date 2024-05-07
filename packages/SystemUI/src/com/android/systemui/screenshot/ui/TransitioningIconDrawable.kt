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

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import androidx.core.animation.doOnEnd
import java.util.Objects

/**  */
class TransitioningIconDrawable : Drawable() {
    // The drawable for the current icon of this view. During icon transitions, this is the one
    // being animated out.
    private var drawable: Drawable? = null

    // The incoming new icon. Only populated during transition animations (when drawable is also
    // non-null).
    private var enteringDrawable: Drawable? = null
    private var colorFilter: ColorFilter? = null
    private var tint: ColorStateList? = null
    private var alpha = 255

    private var transitionAnimator =
        ValueAnimator.ofFloat(0f, 1f).also { it.doOnEnd { onTransitionComplete() } }

    /**
     * Set the drawable to be displayed, potentially animating the transition from one icon to the
     * next.
     */
    fun setIcon(incomingDrawable: Drawable?) {
        if (Objects.equals(drawable, incomingDrawable) && !transitionAnimator.isRunning) {
            return
        }

        incomingDrawable?.colorFilter = colorFilter
        incomingDrawable?.setTintList(tint)

        if (drawable == null) {
            // No existing icon drawn, just show the new one without a transition
            drawable = incomingDrawable
            invalidateSelf()
            return
        }

        if (enteringDrawable != null) {
            // There's already an entrance animation happening, just update the entering icon, not
            // maintaining a queue or anything.
            enteringDrawable = incomingDrawable
            return
        }

        // There was already an icon, need to animate between icons.
        enteringDrawable = incomingDrawable
        transitionAnimator.setCurrentFraction(0f)
        transitionAnimator.start()
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        // Scale the old one down, scale the new one up.
        drawable?.let {
            val scale =
                if (transitionAnimator.isRunning) {
                    1f - transitionAnimator.animatedFraction
                } else {
                    1f
                }
            drawScaledDrawable(it, canvas, scale)
        }
        enteringDrawable?.let {
            val scale = transitionAnimator.animatedFraction
            drawScaledDrawable(it, canvas, scale)
        }

        if (transitionAnimator.isRunning) {
            invalidateSelf()
        }
    }

    private fun drawScaledDrawable(drawable: Drawable, canvas: Canvas, scale: Float) {
        drawable.bounds = getBounds()
        canvas.save()
        canvas.scale(
            scale,
            scale,
            (drawable.intrinsicWidth / 2).toFloat(),
            (drawable.intrinsicHeight / 2).toFloat()
        )
        drawable.draw(canvas)
        canvas.restore()
    }

    private fun onTransitionComplete() {
        drawable = enteringDrawable
        enteringDrawable = null
        invalidateSelf()
    }

    override fun setTintList(tint: ColorStateList?) {
        super.setTintList(tint)
        drawable?.setTintList(tint)
        enteringDrawable?.setTintList(tint)
        this.tint = tint
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        drawable?.colorFilter = colorFilter
        enteringDrawable?.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = alpha
}
