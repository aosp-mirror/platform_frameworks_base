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
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Xfermode
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.MathUtils
import android.view.View
import androidx.annotation.Keep
import com.android.app.animation.Interpolators
import com.android.internal.graphics.ColorUtils
import com.android.internal.graphics.ColorUtils.blendARGB
import com.android.systemui.res.R
import org.xmlpull.v1.XmlPullParser

private const val BACKGROUND_ANIM_DURATION = 370L

/** Drawable that can draw an animated gradient when tapped. */
@Keep
class IlluminationDrawable : Drawable() {

    private var themeAttrs: IntArray? = null
    private var cornerRadiusOverride = -1f
    var cornerRadius = 0f
        get() {
            return if (cornerRadiusOverride >= 0) {
                cornerRadiusOverride
            } else {
                field
            }
        }
    private var highlightColor = Color.TRANSPARENT
    private var tmpHsl = floatArrayOf(0f, 0f, 0f)
    private var paint = Paint()
    private var highlight = 0f
    private val lightSources = arrayListOf<LightSourceDrawable>()

    private var backgroundColor = Color.TRANSPARENT
        set(value) {
            if (value == field) {
                return
            }
            field = value
            animateBackground()
        }

    private var backgroundAnimation: ValueAnimator? = null

    /** Draw background and gradient. */
    override fun draw(canvas: Canvas) {
        canvas.drawRoundRect(
            0f,
            0f,
            bounds.width().toFloat(),
            bounds.height().toFloat(),
            cornerRadius,
            cornerRadius,
            paint
        )
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, cornerRadius)
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
        if (a.hasValue(R.styleable.IlluminationDrawable_cornerRadius)) {
            cornerRadius =
                a.getDimension(R.styleable.IlluminationDrawable_cornerRadius, cornerRadius)
        }
        if (a.hasValue(R.styleable.IlluminationDrawable_highlight)) {
            highlight = a.getInteger(R.styleable.IlluminationDrawable_highlight, 0) / 100f
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

        lightSources.forEach { it.alpha = alpha }
    }

    override fun getAlpha(): Int {
        return paint.alpha
    }

    override fun setXfermode(mode: Xfermode?) {
        if (mode == paint.xfermode) {
            return
        }

        paint.xfermode = mode
        invalidateSelf()
    }

    /**
     * Cross fade background.
     *
     * @see setTintList
     * @see backgroundColor
     */
    private fun animateBackground() {
        ColorUtils.colorToHSL(backgroundColor, tmpHsl)
        val L = tmpHsl[2]
        tmpHsl[2] =
            MathUtils.constrain(
                if (L < 1f - highlight) {
                    L + highlight
                } else {
                    L - highlight
                },
                0f,
                1f
            )

        val initialBackground = paint.color
        val initialHighlight = highlightColor
        val finalHighlight = ColorUtils.HSLToColor(tmpHsl)

        backgroundAnimation?.cancel()
        backgroundAnimation =
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = BACKGROUND_ANIM_DURATION
                interpolator = Interpolators.FAST_OUT_LINEAR_IN
                addUpdateListener {
                    val progress = it.animatedValue as Float
                    paint.color = blendARGB(initialBackground, backgroundColor, progress)
                    highlightColor = blendARGB(initialHighlight, finalHighlight, progress)
                    lightSources.forEach { it.highlightColor = highlightColor }
                    invalidateSelf()
                }
                addListener(
                    object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            backgroundAnimation = null
                        }
                    }
                )
                start()
            }
    }

    override fun setTintList(tint: ColorStateList?) {
        super.setTintList(tint)
        backgroundColor = tint!!.defaultColor
    }

    fun registerLightSource(lightSource: View) {
        if (lightSource.background is LightSourceDrawable) {
            registerLightSource(lightSource.background as LightSourceDrawable)
        } else if (lightSource.foreground is LightSourceDrawable) {
            registerLightSource(lightSource.foreground as LightSourceDrawable)
        }
    }

    private fun registerLightSource(lightSource: LightSourceDrawable) {
        lightSource.alpha = paint.alpha
        lightSources.add(lightSource)
    }

    /** Set or remove the corner radius override. This is typically set during animations. */
    fun setCornerRadiusOverride(cornerRadius: Float?) {
        cornerRadiusOverride = cornerRadius ?: -1f
    }
}
