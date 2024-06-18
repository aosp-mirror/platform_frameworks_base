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
package com.android.wm.shell.windowdecor

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import androidx.core.content.ContextCompat
import com.android.wm.shell.R

private const val OPEN_MAXIMIZE_MENU_DELAY_ON_HOVER_MS = 350
private const val MAX_DRAWABLE_ALPHA = 255

class MaximizeButtonView(
        context: Context,
        attrs: AttributeSet
) : FrameLayout(context, attrs) {
    lateinit var onHoverAnimationFinishedListener: () -> Unit
    private val hoverProgressAnimatorSet = AnimatorSet()
    var hoverDisabled = false

    private val progressBar: ProgressBar
    private val maximizeWindow: ImageButton

    init {
        LayoutInflater.from(context).inflate(R.layout.maximize_menu_button, this, true)

        progressBar = requireViewById(R.id.progress_bar)
        maximizeWindow = requireViewById(R.id.maximize_window)
    }

    fun startHoverAnimation() {
        if (hoverDisabled) return
        if (hoverProgressAnimatorSet.isRunning) {
            cancelHoverAnimation()
        }

        maximizeWindow.background.alpha = 0

        hoverProgressAnimatorSet.playSequentially(
                ValueAnimator.ofInt(0, MAX_DRAWABLE_ALPHA)
                        .setDuration(50)
                        .apply {
                            addUpdateListener {
                                maximizeWindow.background.alpha = animatedValue as Int
                            }
                        },
                ObjectAnimator.ofInt(progressBar, "progress", 100)
                        .setDuration(OPEN_MAXIMIZE_MENU_DELAY_ON_HOVER_MS.toLong())
                        .apply {
                            doOnStart {
                                progressBar.setProgress(0, false)
                                progressBar.visibility = View.VISIBLE
                            }
                            doOnEnd {
                                progressBar.visibility = View.INVISIBLE
                                onHoverAnimationFinishedListener()
                            }
                        }
        )
        hoverProgressAnimatorSet.start()
    }

    fun cancelHoverAnimation() {
        hoverProgressAnimatorSet.removeAllListeners()
        hoverProgressAnimatorSet.cancel()
        progressBar.visibility = View.INVISIBLE
    }

    fun setAnimationTints(darkMode: Boolean) {
        if (darkMode) {
            progressBar.progressTintList = ColorStateList.valueOf(
                    resources.getColor(R.color.desktop_mode_maximize_menu_progress_dark))
            maximizeWindow.background?.setTintList(ContextCompat.getColorStateList(context,
                    R.color.desktop_mode_caption_button_color_selector_dark))
        } else {
            progressBar.progressTintList = ColorStateList.valueOf(
                    resources.getColor(R.color.desktop_mode_maximize_menu_progress_light))
            maximizeWindow.background?.setTintList(ContextCompat.getColorStateList(context,
                    R.color.desktop_mode_caption_button_color_selector_light))
        }
    }
}
