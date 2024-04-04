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

package com.android.systemui.media.controls.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.MathUtils.lerp
import androidx.annotation.Keep
import com.android.app.animation.Interpolators
import com.android.internal.graphics.ColorUtils
import com.android.systemui.R
import org.xmlpull.v1.XmlPullParser

private const val RIPPLE_ANIM_DURATION = 800L
private const val RIPPLE_DOWN_PROGRESS = 0.05f
private const val RIPPLE_CANCEL_DURATION = 200L
private val GRADIENT_STOPS = floatArrayOf(0.2f, 1f)

private data class RippleData(
    var x: Float,
    var y: Float,
    var alpha: Float,
    var progress: Float,
    var minSize: Float,
    var maxSize: Float,
    var highlight: Float
)

/** Drawable that can draw an animated gradient when tapped. */
@Keep
class LightSourceDrawable : Drawable() {

    private var pressed = false
    private var themeAttrs: IntArray? = null
    private val rippleData = RippleData(0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private var paint = Paint()

    var highlightColor = Color.WHITE
        set(value) {
            if (field == value) {
                return
            }
            field = value
            invalidateSelf()
        }

    /** Draw a small highlight under the finger before expanding (or cancelling) it. */
    private var active: Boolean = false
        set(value) {
            if (value == field) {
                return
            }
            field = value

            if (value) {
                rippleAnimation?.cancel()
                rippleData.alpha = 1f
                rippleData.progress = RIPPLE_DOWN_PROGRESS
            } else {
                rippleAnimation?.cancel()
                rippleAnimation =
                    ValueAnimator.ofFloat(rippleData.alpha, 0f).apply {
                        duration = RIPPLE_CANCEL_DURATION
                        interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                        addUpdateListener {
                            rippleData.alpha = it.animatedValue as Float
                            invalidateSelf()
                        }
                        addListener(
                            object : AnimatorListenerAdapter() {
                                var cancelled = false
                                override fun onAnimationCancel(animation: Animator) {
                                    cancelled = true
                                }

                                override fun onAnimationEnd(animation: Animator) {
                                    if (cancelled) {
                                        return
                                    }
                                    rippleData.progress = 0f
                                    rippleData.alpha = 0f
                                    rippleAnimation = null
                                    invalidateSelf()
                                }
                            }
                        )
                        start()
                    }
            }
            invalidateSelf()
        }

    private var rippleAnimation: Animator? = null

    /** Draw background and gradient. */
    override fun draw(canvas: Canvas) {
        val radius = lerp(rippleData.minSize, rippleData.maxSize, rippleData.progress)
        val centerColor =
            ColorUtils.setAlphaComponent(highlightColor, (rippleData.alpha * 255).toInt())
        paint.shader =
            RadialGradient(
                rippleData.x,
                rippleData.y,
                radius,
                intArrayOf(centerColor, Color.TRANSPARENT),
                GRADIENT_STOPS,
                Shader.TileMode.CLAMP
            )
        canvas.drawCircle(rippleData.x, rippleData.y, radius, paint)
    }

    override fun getOutline(outline: Outline) {
        // No bounds, parent will clip it
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSPARENT
    }

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        val a = obtainAttributes(r, theme, attrs, R.styleable.IlluminationDrawable)
        themeAttrs = a.extractThemeAttrs()
        updateStateFromTypedArray(a)
        a.recycle()
    }

    private fun updateStateFromTypedArray(a: TypedArray) {
        if (a.hasValue(R.styleable.IlluminationDrawable_rippleMinSize)) {
            rippleData.minSize = a.getDimension(R.styleable.IlluminationDrawable_rippleMinSize, 0f)
        }
        if (a.hasValue(R.styleable.IlluminationDrawable_rippleMaxSize)) {
            rippleData.maxSize = a.getDimension(R.styleable.IlluminationDrawable_rippleMaxSize, 0f)
        }
        if (a.hasValue(R.styleable.IlluminationDrawable_highlight)) {
            rippleData.highlight =
                a.getInteger(R.styleable.IlluminationDrawable_highlight, 0) / 100f
        }
    }

    override fun canApplyTheme(): Boolean {
        return themeAttrs != null && themeAttrs!!.size > 0 || super.canApplyTheme()
    }

    override fun applyTheme(t: Resources.Theme) {
        super.applyTheme(t)
        themeAttrs?.let {
            val a = t.resolveAttributes(it, R.styleable.IlluminationDrawable)
            updateStateFromTypedArray(a)
            a.recycle()
        }
    }

    override fun setColorFilter(p0: ColorFilter?) {
        throw UnsupportedOperationException("Color filters are not supported")
    }

    override fun setAlpha(alpha: Int) {
        if (alpha == paint.alpha) {
            return
        }

        paint.alpha = alpha
        invalidateSelf()
    }

    /** Draws an animated ripple that expands fading away. */
    private fun illuminate() {
        rippleData.alpha = 1f
        invalidateSelf()

        rippleAnimation?.cancel()
        rippleAnimation =
            AnimatorSet().apply {
                playTogether(
                    ValueAnimator.ofFloat(1f, 0f).apply {
                        startDelay = 133
                        duration = RIPPLE_ANIM_DURATION - startDelay
                        interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                        addUpdateListener {
                            rippleData.alpha = it.animatedValue as Float
                            invalidateSelf()
                        }
                    },
                    ValueAnimator.ofFloat(rippleData.progress, 1f).apply {
                        duration = RIPPLE_ANIM_DURATION
                        interpolator = Interpolators.LINEAR_OUT_SLOW_IN
                        addUpdateListener {
                            rippleData.progress = it.animatedValue as Float
                            invalidateSelf()
                        }
                    }
                )
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            rippleData.progress = 0f
                            rippleAnimation = null
                            invalidateSelf()
                        }
                    }
                )
                start()
            }
    }

    override fun setHotspot(x: Float, y: Float) {
        rippleData.x = x
        rippleData.y = y
        if (active) {
            invalidateSelf()
        }
    }

    override fun isStateful(): Boolean {
        return true
    }

    override fun hasFocusStateSpecified(): Boolean {
        return true
    }

    override fun isProjected(): Boolean {
        return true
    }

    override fun getDirtyBounds(): Rect {
        val radius = lerp(rippleData.minSize, rippleData.maxSize, rippleData.progress)
        val bounds =
            Rect(
                (rippleData.x - radius).toInt(),
                (rippleData.y - radius).toInt(),
                (rippleData.x + radius).toInt(),
                (rippleData.y + radius).toInt()
            )
        bounds.union(super.getDirtyBounds())
        return bounds
    }

    override fun onStateChange(stateSet: IntArray): Boolean {
        val changed = super.onStateChange(stateSet)

        val wasPressed = pressed
        var enabled = false
        pressed = false
        var focused = false
        var hovered = false

        for (state in stateSet) {
            when (state) {
                com.android.internal.R.attr.state_enabled -> {
                    enabled = true
                }
                com.android.internal.R.attr.state_focused -> {
                    focused = true
                }
                com.android.internal.R.attr.state_pressed -> {
                    pressed = true
                }
                com.android.internal.R.attr.state_hovered -> {
                    hovered = true
                }
            }
        }

        active = enabled && (pressed || focused || hovered)
        if (wasPressed && !pressed) {
            illuminate()
        }

        return changed
    }
}
