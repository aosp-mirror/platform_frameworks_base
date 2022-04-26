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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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

/** A generic implementation of [ColorTransition] so that we can define a factory method. */
open class GenericColorTransition(
    private val applyTheme: (ColorScheme?) -> Unit
) : ColorTransition {
    override fun updateColorScheme(scheme: ColorScheme?) = applyTheme(scheme)
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
typealias GenericColorTransitionFactory = ((ColorScheme?) -> Unit) -> GenericColorTransition

/**
 * ColorSchemeTransition constructs a ColorTransition for each color in the scheme
 * that needs to be transitioned when changed. It also sets up the assignment functions for sending
 * the sending the interpolated colors to the appropriate views.
 */
class ColorSchemeTransition internal constructor(
    private val context: Context,
    mediaViewHolder: MediaViewHolder,
    animatingColorTransitionFactory: AnimatingColorTransitionFactory,
    genericColorTransitionFactory: GenericColorTransitionFactory
) {
    constructor(context: Context, mediaViewHolder: MediaViewHolder) :
        this(context, mediaViewHolder, ::AnimatingColorTransition, ::GenericColorTransition)

    val bgColor = context.getColor(com.android.systemui.R.color.material_dynamic_secondary95)

    val surfaceColor = animatingColorTransitionFactory(
        bgColor,
        ::surfaceFromScheme
    ) { surfaceColor ->
        val colorList = ColorStateList.valueOf(surfaceColor)
        mediaViewHolder.player.backgroundTintList = colorList
        mediaViewHolder.seamlessIcon.imageTintList = colorList
        mediaViewHolder.seamlessText.setTextColor(surfaceColor)
        mediaViewHolder.gutsViewHolder.setSurfaceColor(surfaceColor)
    }

    val accentPrimary = animatingColorTransitionFactory(
        loadDefaultColor(R.attr.textColorPrimary),
        ::accentPrimaryFromScheme
    ) { accentPrimary ->
        val accentColorList = ColorStateList.valueOf(accentPrimary)
        mediaViewHolder.actionPlayPause.backgroundTintList = accentColorList
        mediaViewHolder.gutsViewHolder.setAccentPrimaryColor(accentPrimary)
        mediaViewHolder.seamlessButton.backgroundTintList = accentColorList
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

    // Note: This background gradient currently doesn't animate between colors.
    val backgroundGradient = genericColorTransitionFactory { scheme ->
        val defaultTintColor = ColorStateList.valueOf(bgColor)
        if (scheme == null) {
            mediaViewHolder.albumView.foregroundTintList = defaultTintColor
            mediaViewHolder.albumView.backgroundTintList = defaultTintColor
            return@genericColorTransitionFactory
        }

        // If there's no album art, just hide the gradient so we show the solid background.
        val showGradient = mediaViewHolder.albumView.drawable != null
        val startColor = getColorWithAlpha(
            backgroundStartFromScheme(scheme),
            alpha = if (showGradient) .25f else 0f
        )
        val endColor = getColorWithAlpha(
            backgroundEndFromScheme(scheme),
            alpha = if (showGradient) .90f else 0f
        )
        val gradientColors = intArrayOf(startColor, endColor)

        val foregroundGradient = mediaViewHolder.albumView.foreground?.mutate()
        if (foregroundGradient is GradientDrawable) {
            foregroundGradient.colors = gradientColors
        }
        val backgroundGradient = mediaViewHolder.albumView.background?.mutate()
        if (backgroundGradient is GradientDrawable) {
            backgroundGradient.colors = gradientColors
        }
    }

    val colorTransitions = arrayOf(
        surfaceColor,
        accentPrimary,
        accentSecondary,
        textPrimary,
        textPrimaryInverse,
        textSecondary,
        textTertiary,
        backgroundGradient
    )

    private fun loadDefaultColor(id: Int): Int {
        return Utils.getColorAttr(context, id).defaultColor
    }

    fun updateColorScheme(colorScheme: ColorScheme?) {
        colorTransitions.forEach { it.updateColorScheme(colorScheme) }
    }
}
