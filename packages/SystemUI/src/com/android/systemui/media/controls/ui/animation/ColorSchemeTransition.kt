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

package com.android.systemui.media.controls.ui.animation

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.animation.ValueAnimator.AnimatorUpdateListener
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.graphics.drawable.RippleDrawable
import com.android.internal.R
import com.android.internal.annotations.VisibleForTesting
import com.android.settingslib.Utils
import com.android.systemui.Flags
import com.android.systemui.media.controls.ui.view.MediaViewHolder
import com.android.systemui.monet.ColorScheme
import com.android.systemui.surfaceeffects.loadingeffect.LoadingEffect
import com.android.systemui.surfaceeffects.ripple.MultiRippleController
import com.android.systemui.surfaceeffects.turbulencenoise.TurbulenceNoiseController

/**
 * A [ColorTransition] is an object that updates the colors of views each time [updateColorScheme]
 * is triggered.
 */
interface ColorTransition {
    fun updateColorScheme(scheme: ColorScheme?): Boolean
}

/**
 * A [ColorTransition] that animates between two specific colors. It uses a ValueAnimator to execute
 * the animation and interpolate between the source color and the target color.
 *
 * Selection of the target color from the scheme, and application of the interpolated color are
 * delegated to callbacks.
 */
open class AnimatingColorTransition(
    private val defaultColor: Int,
    private val extractColor: (ColorScheme) -> Int,
    private val applyColor: (Int) -> Unit,
) : AnimatorUpdateListener, ColorTransition {

    private val argbEvaluator = ArgbEvaluator()
    private val valueAnimator = buildAnimator()
    var sourceColor: Int = defaultColor
    var currentColor: Int = defaultColor
    var targetColor: Int = defaultColor

    override fun onAnimationUpdate(animation: ValueAnimator) {
        currentColor =
            argbEvaluator.evaluate(animation.animatedFraction, sourceColor, targetColor) as Int
        applyColor(currentColor)
    }

    override fun updateColorScheme(scheme: ColorScheme?): Boolean {
        val newTargetColor = if (scheme == null) defaultColor else extractColor(scheme)
        if (newTargetColor != targetColor) {
            sourceColor = currentColor
            targetColor = newTargetColor
            valueAnimator.cancel()
            valueAnimator.start()
            return true
        }
        return false
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
 * ColorSchemeTransition constructs a ColorTransition for each color in the scheme that needs to be
 * transitioned when changed. It also sets up the assignment functions for sending the sending the
 * interpolated colors to the appropriate views.
 */
class ColorSchemeTransition
internal constructor(
    private val context: Context,
    private val mediaViewHolder: MediaViewHolder,
    private val multiRippleController: MultiRippleController,
    private val turbulenceNoiseController: TurbulenceNoiseController,
    animatingColorTransitionFactory: AnimatingColorTransitionFactory,
) {
    constructor(
        context: Context,
        mediaViewHolder: MediaViewHolder,
        multiRippleController: MultiRippleController,
        turbulenceNoiseController: TurbulenceNoiseController,
    ) : this(
        context,
        mediaViewHolder,
        multiRippleController,
        turbulenceNoiseController,
        ::AnimatingColorTransition,
    )

    var loadingEffect: LoadingEffect? = null

    val bgColor =
        if (Flags.mediaControlsUiUpdate()) {
            context.getColor(R.color.materialColorOnSurface)
        } else {
            context.getColor(com.google.android.material.R.color.material_dynamic_neutral20)
        }

    val textColor = context.getColor(R.color.materialColorInverseOnSurface)
    val buttonBgColor = context.getColor(R.color.materialColorPrimary)
    val insideButtonColor = context.getColor(R.color.materialColorOnPrimary)

    val surfaceColor =
        animatingColorTransitionFactory(bgColor, ::surfaceFromScheme) { surfaceColor ->
            val colorList = ColorStateList.valueOf(surfaceColor)
            mediaViewHolder.albumView.backgroundTintList = colorList
            mediaViewHolder.gutsViewHolder.setSurfaceColor(surfaceColor)

            if (Flags.mediaControlsUiUpdate()) return@animatingColorTransitionFactory
            mediaViewHolder.seamlessIcon.imageTintList = colorList
            mediaViewHolder.seamlessText.setTextColor(surfaceColor)
        }
    val accentPrimary =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                buttonBgColor
            } else {
                loadDefaultColor(R.attr.textColorPrimary)
            },
            ::accentPrimaryFromScheme,
        ) { accentPrimary ->
            val accentColorList = ColorStateList.valueOf(accentPrimary)
            mediaViewHolder.actionPlayPause.backgroundTintList = accentColorList
            mediaViewHolder.gutsViewHolder.setAccentPrimaryColor(accentPrimary)
            multiRippleController.updateColor(accentPrimary)
            turbulenceNoiseController.updateNoiseColor(accentPrimary)
            loadingEffect?.updateColor(accentPrimary)
        }

    val accentSecondary =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                buttonBgColor
            } else {
                loadDefaultColor(R.attr.textColorPrimary)
            },
            ::accentSecondaryFromScheme,
        ) { accentSecondary ->
            val colorList = ColorStateList.valueOf(accentSecondary)
            (mediaViewHolder.seamlessButton.background as? RippleDrawable)?.let {
                it.setColor(colorList)
                it.effectColor = colorList
            }
        }

    val colorSeamless =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                buttonBgColor
            } else {
                loadDefaultColor(R.attr.textColorPrimary)
            },
            { colorScheme: ColorScheme ->
                // A1-100 dark in dark theme, A1-200 in light theme
                if (
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        UI_MODE_NIGHT_YES
                )
                    colorScheme.accent1.s100
                else colorScheme.accent1.s200
            },
            { seamlessColor: Int ->
                val accentColorList = ColorStateList.valueOf(seamlessColor)
                mediaViewHolder.seamlessButton.backgroundTintList = accentColorList
            },
        )

    val textPrimary =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                textColor
            } else {
                loadDefaultColor(R.attr.textColorPrimary)
            },
            ::textPrimaryFromScheme,
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

    val textPrimaryInverse =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                insideButtonColor
            } else {
                loadDefaultColor(R.attr.textColorPrimaryInverse)
            },
            ::textPrimaryInverseFromScheme,
        ) { textPrimaryInverse ->
            val colorList = ColorStateList.valueOf(textPrimaryInverse)
            mediaViewHolder.actionPlayPause.imageTintList = colorList

            if (!Flags.mediaControlsUiUpdate()) return@animatingColorTransitionFactory
            mediaViewHolder.seamlessIcon.imageTintList = colorList
            mediaViewHolder.seamlessText.setTextColor(textPrimaryInverse)
        }

    val textSecondary =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                textColor
            } else {
                loadDefaultColor(R.attr.textColorSecondary)
            },
            ::textSecondaryFromScheme,
        ) { textSecondary ->
            mediaViewHolder.artistText.setTextColor(textSecondary)
        }

    val textTertiary =
        animatingColorTransitionFactory(
            if (Flags.mediaControlsUiUpdate()) {
                textColor
            } else {
                loadDefaultColor(R.attr.textColorTertiary)
            },
            ::textTertiaryFromScheme,
        ) { textTertiary ->
            mediaViewHolder.seekBar.progressBackgroundTintList =
                ColorStateList.valueOf(textTertiary)
        }

    val colorTransitions =
        arrayOf(
            surfaceColor,
            colorSeamless,
            accentPrimary,
            accentSecondary,
            textPrimary,
            textPrimaryInverse,
            textSecondary,
            textTertiary,
        )

    private fun loadDefaultColor(id: Int): Int {
        return Utils.getColorAttr(context, id).defaultColor
    }

    fun updateColorScheme(colorScheme: ColorScheme?): Boolean {
        var anyChanged = false
        colorTransitions.forEach {
            val isChanged = it.updateColorScheme(colorScheme)

            // Ignore changes to colorSeamless, since that is expected when toggling dark mode
            if (it == colorSeamless) return@forEach

            anyChanged = isChanged || anyChanged
        }
        colorScheme?.let { mediaViewHolder.gutsViewHolder.colorScheme = colorScheme }
        return anyChanged
    }
}
