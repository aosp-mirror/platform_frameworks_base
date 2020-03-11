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

package com.android.systemui.controls

import android.annotation.StringRes
import android.content.Context
import android.graphics.CornerPathEffect
import android.graphics.drawable.ShapeDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.android.systemui.Prefs
import com.android.systemui.R
import com.android.systemui.recents.TriangleShape

/**
 * Manager for showing an onboarding tooltip on screen.
 *
 * The tooltip can be made to appear below or above a point. The number of times it will appear
 * is determined by an shared preference (defined in [Prefs]).
 *
 * @property context A context to use to inflate the views and retrieve shared preferences from
 * @property preferenceName name of the preference to use to track the number of times the tooltip
 *                          has been shown.
 * @property maxTimesShown the maximum number of times to show the tooltip
 * @property below whether the tooltip should appear below (with up pointing arrow) or above (down
 *                 pointing arrow) the specified point.
 * @see [TooltipManager.show]
 */
class TooltipManager(
    context: Context,
    private val preferenceName: String,
    private val maxTimesShown: Int = 2,
    private val below: Boolean = true
) {

    companion object {
        private const val SHOW_DELAY_MS: Long = 500
        private const val SHOW_DURATION_MS: Long = 300
        private const val HIDE_DURATION_MS: Long = 100
    }

    private var shown = Prefs.getInt(context, preferenceName, 0)

    val layout: ViewGroup =
        LayoutInflater.from(context).inflate(R.layout.controls_onboarding, null) as ViewGroup
    val preferenceStorer = { num: Int ->
        Prefs.putInt(context, preferenceName, num)
    }

    init {
        layout.alpha = 0f
    }

    private val textView = layout.requireViewById<TextView>(R.id.onboarding_text)
    private val dismissView = layout.requireViewById<View>(R.id.dismiss).apply {
        setOnClickListener {
            hide(true)
        }
    }

    private val arrowView = layout.requireViewById<View>(R.id.arrow).apply {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        val toastColor = context.resources.getColor(typedValue.resourceId, context.theme)
        val arrowRadius = context.resources.getDimensionPixelSize(
            R.dimen.recents_onboarding_toast_arrow_corner_radius)
        val arrowLp = layoutParams
        val arrowDrawable = ShapeDrawable(TriangleShape.create(
            arrowLp.width.toFloat(), arrowLp.height.toFloat(), below))
        val arrowPaint = arrowDrawable.paint
        arrowPaint.color = toastColor
        // The corner path effect won't be reflected in the shadow, but shouldn't be noticeable.
        arrowPaint.pathEffect = CornerPathEffect(arrowRadius.toFloat())
        setBackground(arrowDrawable)
    }

    init {
        if (!below) {
            layout.removeView(arrowView)
            layout.addView(arrowView)
            (arrowView.layoutParams as ViewGroup.MarginLayoutParams).apply {
                bottomMargin = topMargin
                topMargin = 0
            }
        }
    }

    /**
     * Show the tooltip
     *
     * @param stringRes the id of the string to show in the tooltip
     * @param x horizontal position (w.r.t. screen) for the arrow point
     * @param y vertical position (w.r.t. screen) for the arrow point
     */
    fun show(@StringRes stringRes: Int, x: Int, y: Int) {
        if (!shouldShow()) return
        textView.setText(stringRes)
        shown++
        preferenceStorer(shown)
        layout.post {
            val p = IntArray(2)
            layout.getLocationOnScreen(p)
            layout.translationX = (x - p[0] - layout.width / 2).toFloat()
            layout.translationY = (y - p[1]).toFloat() - if (!below) layout.height else 0
            if (layout.alpha == 0f) {
                layout.animate()
                    .alpha(1f)
                    .withLayer()
                    .setStartDelay(SHOW_DELAY_MS)
                    .setDuration(SHOW_DURATION_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    /**
     * Hide the tooltip
     *
     * @param animate whether to animate the fade out
     */
    fun hide(animate: Boolean = false) {
        if (layout.alpha == 0f) return
        layout.post {
            if (animate) {
                layout.animate()
                    .alpha(0f)
                    .withLayer()
                    .setStartDelay(0)
                    .setDuration(HIDE_DURATION_MS)
                    .setInterpolator(AccelerateInterpolator())
                    .start()
            } else {
                layout.animate().cancel()
                layout.alpha = 0f
            }
        }
    }

    private fun shouldShow() = shown < maxTimesShown
}