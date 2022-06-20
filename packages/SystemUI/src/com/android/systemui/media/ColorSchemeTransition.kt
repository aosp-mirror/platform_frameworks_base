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

package com.android.systemui.media

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import com.android.internal.R
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.monet.ColorScheme
import com.android.systemui.util.getColorWithAlpha

/**
 * A [ColorTransition] is an object that updates the colors of views each time [updateColorScheme]
 * is triggered.
 */
interface ColorTransition {
    fun updateColorScheme(scheme: ColorScheme?)
}

/**
 * A [ColorTransition] that animates between two specific colors.
 * It uses a ValueAnimator to execute the animation and interpolate between the source color and
 * the target color.
 *
 * Selection of the target color from the scheme, and application of the interpolated color
 * are delegated to callbacks.
 */
open class AnimatingColorTransition(
    private val defaultColor: Int,
    private val extractColor: (ColorScheme) -> Int,
    private val applyColor: (Int) -> Unit
) : AnimatorUpdateListener, ColorTransition {

    private val argbEvaluator = ArgbEvaluator()
    private val valueAnimator = buildAnimator()
    var sourceColor: Int = defaultColor
    var currentColor: Int = defaultColor
    var targetColor: Int = defaultColor

    override fun onAnimationUpdate(animation: ValueAnimator) {
        currentColor = argbEvaluator.evaluate(
            animation.animatedFraction, sourceColor, targetColor
        ) as Int
        applyColor(currentColor)
    }

    override fun updateColorScheme(scheme: ColorScheme?) {
        val newTargetColor = if (scheme == null) defaultColor else extractColor(scheme)
        if (newTargetColor != targetColor) {
            sourceColor = currentColor
            targetColor = newTargetColor
            valueAnimator.cancel()
            valueAnimator.start()
        }
    }

    init {
        applyColor(defaultColor)
    }

    @VisibleForTesting
    open fun buildAnimator(): ValueAnimator {
        val animator = ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 333
        animator.addUpdateListener(this)
        return animator
    }
}

typealias AnimatingColorTransitionFactory =
            (Int, (ColorScheme) -> Int, (Int) -> Unit) -> AnimatingColorTransition

/**
 * ColorSchemeTransition constructs a ColorTransition for each color in the scheme
 * that needs to be transitioned when changed. It also sets up the assignment functions for sending
 * the sending the interpolated colors to the appropriate views.
 */
class ColorSchemeTransition internal constructor(
    private val context: Context,
    private val mediaViewHolder: MediaViewHolder,
    animatingColorTransitionFactory: AnimatingColorTransitionFactory
) {
    constructor(context: Context, mediaViewHolder: MediaViewHolder) :
        this(context, mediaViewHolder, ::AnimatingColorTransition)

    private var isGradientEnabled = true
    val bgColor = context.getColor(com.android.systemui.R.color.material_dynamic_secondary95)
    val surfaceColor = animatingColorTransitionFactory(
        bgColor,
        ::surfaceFromScheme
    ) { surfaceColor ->
        val colorList = ColorStateList.valueOf(surfaceColor)
        mediaViewHolder.seamlessIcon.imageTintList = colorList
        mediaViewHolder.seamlessText.setTextColor(surfaceColor)
        mediaViewHolder.albumView.backgroundTintList = colorList
        mediaViewHolder.gutsViewHolder.setSurfaceColor(surfaceColor)
    }

    val accentPrimary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::accentPrimaryFromScheme
    ) { accentPrimary ->
        val accentColorList = ColorStateList.valueOf(accentPrimary)
        mediaViewHolder.actionPlayPause.backgroundTintList = accentColorList
        mediaViewHolder.gutsViewHolder.setAccentPrimaryColor(accentPrimary)
    }

    val accentSecondary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::accentSecondaryFromScheme
    ) { accentSecondary ->
        val colorList = ColorStateList.valueOf(accentSecondary)
        (mediaViewHolder.seamlessButton.background as? RippleDrawable)?.let {
            it.setColor(colorList)
            it.effectColor = colorList
        }
    }

    val colorSeamless = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        { colorScheme: ColorScheme ->
            // A1-100 dark in dark theme, A1-200 in light theme
            if (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES)
                        colorScheme.accent1[2]
                        else colorScheme.accent1[3]
        }, { seamlessColor: Int ->
            val accentColorList = ColorStateList.valueOf(seamlessColor)
            mediaViewHolder.seamlessButton.backgroundTintList = accentColorList
    })

    val textPrimary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::textPrimaryFromScheme
    ) { textPrimary ->
        mediaViewHolder.titleText.setTextColor(textPrimary)
        val textColorList = ColorStateList.valueOf(textPrimary)
        mediaViewHolder.seekBar.thumb.setTintList(textColorList)
        mediaViewHolder.seekBar.progressTintList = textColorList
        mediaViewHolder.scrubbingElapsedTimeView.setTextColor(textColorList)
        mediaViewHolder.scrubbingTotalTimeView.setTextColor(textColorList)
        for (button in mediaViewHolder.getTransparentActionButtons()) {
            button.imageTintList = textColorList
        }
        mediaViewHolder.gutsViewHolder.setTextPrimaryColor(textPrimary)
    }

    val textPrimaryInverse = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimaryInverse),
        ::textPrimaryInverseFromScheme
    ) { textPrimaryInverse ->
        mediaViewHolder.actionPlayPause.imageTintList = ColorStateList.valueOf(textPrimaryInverse)
    }

    val textSecondary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorSecondary),
        ::textSecondaryFromScheme
    ) { textSecondary -> mediaViewHolder.artistText.setTextColor(textSecondary) }

    val textTertiary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorTertiary),
        ::textTertiaryFromScheme
    ) { textTertiary ->
        mediaViewHolder.seekBar.progressBackgroundTintList = ColorStateList.valueOf(textTertiary)
    }

    val bgGradientStart = animatingColorTransitionFactory(
        bgColor,
        albumGradientPicker(::backgroundStartFromScheme, 0.25f)
    ) { _ -> updateAlbumGradient() }

    val bgGradientEnd = animatingColorTransitionFactory(
        bgColor,
        albumGradientPicker(::backgroundEndFromScheme, 0.9f)
    ) { _ -> updateAlbumGradient() }

    val colorTransitions = arrayOf(
        surfaceColor,
        colorSeamless,
        accentPrimary,
        accentSecondary,
        textPrimary,
        textPrimaryInverse,
        textSecondary,
        textTertiary,
        bgGradientStart,
        bgGradientEnd
    )

    private fun updateAlbumGradient() {
        val gradient = mediaViewHolder.albumView.foreground?.mutate()
        if (gradient is GradientDrawable) {
            gradient.colors = intArrayOf(
                bgGradientStart?.currentColor ?: 0,
                bgGradientEnd?.currentColor ?: 0)
        }
    }

    private fun albumGradientPicker(
        inner: (ColorScheme) -> Int,
        targetAlpha: Float
    ): (ColorScheme) -> Int {
        return { scheme ->
            if (isGradientEnabled)
                getColorWithAlpha(inner(scheme), targetAlpha)
            else
                Color.TRANSPARENT
        }
    }

    private fun loadDefaultColor(id: Int): Int {
        return Utils.getColorAttr(context, id).defaultColor
    }

    fun updateColorScheme(colorScheme: ColorScheme?, enableGradient: Boolean) {
        isGradientEnabled = enableGradient
        colorTransitions.forEach { it.updateColorScheme(colorScheme) }
        colorScheme?.let { mediaViewHolder.gutsViewHolder.colorScheme = colorScheme }
    }
}
