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

package com.android.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.util.SparseArray

private const val TAG_WGHT = "wght"
private const val DEFAULT_ANIMATION_DURATION: Long = 300

/**
 * This class provides text animation between two styles.
 *
 * Currently this class can provide text style animation for text weight and text size. For example
 * the simple view that draws text with animating text size is like as follows:
 *
 * <pre>
 * <code>
 *     class SimpleTextAnimation : View {
 *         @JvmOverloads constructor(...)
 *
 *         private val layout: Layout = ... // Text layout, e.g. StaticLayout.
 *
 *         // TextAnimator tells us when needs to be invalidate.
 *         private val animator = TextAnimator(layout) { invalidate() }
 *
 *         override fun onDraw(canvas: Canvas) = animator.draw(canvas)
 *
 *         // Change the text size with animation.
 *         fun setTextSize(sizePx: Float, animate: Boolean) {
 *             animator.setTextStyle(-1 /* unchanged weight */, sizePx, animate)
 *         }
 *     }
 * </code>
 * </pre>
 */
class TextAnimator(
    layout: Layout,
    private val invalidateCallback: () -> Unit
) {
    // Following two members are for mutable for testing purposes.
    internal var textInterpolator: TextInterpolator = TextInterpolator(layout)
    internal var animator: ValueAnimator = ValueAnimator.ofFloat(1f).apply {
        duration = DEFAULT_ANIMATION_DURATION
        addUpdateListener {
            textInterpolator.progress = it.animatedValue as Float
            invalidateCallback()
        }
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                textInterpolator.rebase()
            }
            override fun onAnimationCancel(animation: Animator?) = textInterpolator.rebase()
        })
    }

    private val typefaceCache = SparseArray<Typeface?>()

    fun updateLayout(layout: Layout) {
        textInterpolator.layout = layout
    }

    fun isRunning(): Boolean {
        return animator.isRunning
    }

    fun draw(c: Canvas) = textInterpolator.draw(c)

    /**
     * Set text style with animation.
     *
     * By passing -1 to weight, the view preserve the current weight.
     * By passing -1 to textSize, the view preserve the current text size.
     * Bu passing -1 to duration, the default text animation, 1000ms, is used.
     * By passing false to animate, the text will be updated without animation.
     *
     * @param weight an optional text weight.
     * @param textSize an optional font size.
     * @param colors an optional colors array that must be the same size as numLines passed to
     *  the TextInterpolator
     * @param animate an optional boolean indicating true for showing style transition as animation,
     *                false for immediate style transition. True by default.
     * @param duration an optional animation duration in milliseconds. This is ignored if animate is
     *                 false.
     * @param interpolator an optional time interpolator. If null is passed, last set interpolator
     *                     will be used. This is ignored if animate is false.
     */
    fun setTextStyle(
        weight: Int = -1,
        textSize: Float = -1f,
        color: Int? = null,
        animate: Boolean = true,
        duration: Long = -1L,
        interpolator: TimeInterpolator? = null,
        delay: Long = 0,
        onAnimationEnd: Runnable? = null
    ) {
        if (animate) {
            animator.cancel()
            textInterpolator.rebase()
        }

        if (textSize >= 0) {
            textInterpolator.targetPaint.textSize = textSize
        }
        if (weight >= 0) {
            // Paint#setFontVariationSettings creates Typeface instance from scratch. To reduce the
            // memory impact, cache the typeface result.
            textInterpolator.targetPaint.typeface = typefaceCache.getOrElse(weight) {
                textInterpolator.targetPaint.fontVariationSettings = "'$TAG_WGHT' $weight"
                textInterpolator.targetPaint.typeface
            }
        }
        if (color != null) {
            textInterpolator.targetPaint.color = color
        }
        textInterpolator.onTargetPaintModified()

        if (animate) {
            animator.startDelay = delay
            animator.duration = if (duration == -1L) {
                DEFAULT_ANIMATION_DURATION
            } else {
                duration
            }
            interpolator?.let { animator.interpolator = it }
            if (onAnimationEnd != null) {
                val listener = object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator?) {
                        onAnimationEnd.run()
                        animator.removeListener(this)
                    }
                    override fun onAnimationCancel(animation: Animator?) {
                        animator.removeListener(this)
                    }
                }
                animator.addListener(listener)
            }
            animator.start()
        } else {
            // No animation is requested, thus set base and target state to the same state.
            textInterpolator.progress = 1f
            textInterpolator.rebase()
            invalidateCallback()
        }
    }
}

private fun <V> SparseArray<V>.getOrElse(key: Int, defaultValue: () -> V): V {
    var v = get(key)
    if (v == null) {
        v = defaultValue()
        put(key, v)
    }
    return v
}