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
 * limitations under the License
 */

package com.android.keyguard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.View
import com.android.app.animation.Interpolators
import com.android.settingslib.Utils
import com.android.systemui.keyguard.shared.constants.KeyguardBouncerConstants.ColorId.TITLE

/** Displays security messages for the keyguard bouncer. */
open class BouncerKeyguardMessageArea(context: Context?, attrs: AttributeSet?) :
    KeyguardMessageArea(context, attrs) {
    private val DEFAULT_COLOR = -1
    private var mDefaultColorState: ColorStateList? = null
    private var mNextMessageColorState: ColorStateList? = ColorStateList.valueOf(DEFAULT_COLOR)
    private val animatorSet = AnimatorSet()
    private var textAboutToShow: CharSequence? = null
    protected open val SHOW_DURATION_MILLIS = 150L
    protected open val HIDE_DURATION_MILLIS = 200L

    override fun onFinishInflate() {
        super.onFinishInflate()
        mDefaultColorState = getColorInStyle()
    }

    private fun getColorInStyle(): ColorStateList? {
        val styledAttributes =
            context.obtainStyledAttributes(styleResId, intArrayOf(android.R.attr.textColor))
        var colorStateList: ColorStateList? = null
        if (styledAttributes != null) {
            colorStateList = styledAttributes.getColorStateList(0)
        }
        styledAttributes.recycle()
        return colorStateList
    }

    override fun updateTextColor() {
        var colorState = mDefaultColorState
        mNextMessageColorState?.defaultColor?.let { color ->
            if (color != DEFAULT_COLOR) {
                colorState = mNextMessageColorState
                mNextMessageColorState = mDefaultColorState ?: ColorStateList.valueOf(DEFAULT_COLOR)
            }
        }
        setTextColor(colorState)
    }

    override fun setNextMessageColor(colorState: ColorStateList?) {
        mNextMessageColorState = colorState
    }

    override fun onThemeChanged() {
        mDefaultColorState = getColorInStyle() ?: Utils.getColorAttr(context, TITLE)
        super.onThemeChanged()
    }

    override fun reloadColor() {
        mDefaultColorState = getColorInStyle() ?: Utils.getColorAttr(context, TITLE)
        super.reloadColor()
    }

    override fun setMessage(msg: CharSequence?, animate: Boolean) {
        if ((msg == textAboutToShow && msg != null) || msg == text) {
            return
        }

        if (!animate) {
            super.setMessage(msg, animate)
            return
        }

        textAboutToShow = msg

        if (animatorSet.isRunning) {
            animatorSet.cancel()
            textAboutToShow = null
        }

        val hideAnimator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f).apply {
                duration = HIDE_DURATION_MILLIS
                interpolator = Interpolators.STANDARD_ACCELERATE
            }

        hideAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    super@BouncerKeyguardMessageArea.setMessage(msg, animate)
                }
            }
        )
        val showAnimator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 0f, 1f).apply {
                duration = SHOW_DURATION_MILLIS
                interpolator = Interpolators.STANDARD_DECELERATE
            }

        showAnimator.addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    textAboutToShow = null
                }
            }
        )

        animatorSet.playSequentially(hideAnimator, showAnimator)
        animatorSet.start()
    }
}
