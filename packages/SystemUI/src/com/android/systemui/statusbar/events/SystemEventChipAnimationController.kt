/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Point
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.View.MeasureSpec.AT_MOST
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.android.systemui.R
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider
import com.android.systemui.statusbar.window.StatusBarWindowController
import javax.inject.Inject

/**
 * Controls the view for system event animations.
 */
class SystemEventChipAnimationController @Inject constructor(
    private val context: Context,
    private val statusBarWindowController: StatusBarWindowController,
    private val contentInsetsProvider: StatusBarContentInsetsProvider
) : SystemStatusChipAnimationCallback {

    private lateinit var animationWindowView: FrameLayout

    private var currentAnimatedView: BackgroundAnimatableView? = null

    // Left for LTR, Right for RTL
    private var animationDirection = LEFT
    private var chipRight = 0
    private var chipLeft = 0
    private var chipWidth = 0
    private var dotCenter = Point(0, 0)
    private var dotSize = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dot_diameter)
    // If the chip animates away to a persistent dot, then we modify the CHIP_OUT animation
    private var isAnimatingToDot = false

    // TODO: move to dagger
    private var initialized = false

    override fun onChipAnimationStart(
        viewCreator: ViewCreator,
        @SystemAnimationState state: Int
    ) {
        if (!initialized) init()

        if (state == ANIMATING_IN) {
            animationDirection = if (animationWindowView.isLayoutRtl) RIGHT else LEFT

            // Initialize the animated view
            val insets = contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation()
            currentAnimatedView = viewCreator(context).also {
                animationWindowView.addView(
                        it.view,
                        layoutParamsDefault(
                                if (animationWindowView.isLayoutRtl) insets.first
                                else insets.second))
                it.view.alpha = 0f
                // For some reason, the window view's measured width is always 0 here, so use the
                // parent (status bar)
                it.view.measure(
                        View.MeasureSpec.makeMeasureSpec(
                                (animationWindowView.parent as View).width, AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(animationWindowView.height, AT_MOST))
                chipWidth = it.chipWidth
            }

            // decide which direction we're animating from, and then set some screen coordinates
            val contentRect = contentInsetsProvider.getStatusBarContentAreaForCurrentRotation()
            when (animationDirection) {
                LEFT -> {
                    chipRight = contentRect.right
                    chipLeft = contentRect.right - chipWidth
                }
                else /* RIGHT */ -> {
                    chipLeft = contentRect.left
                    chipRight = contentRect.left + chipWidth
                }
            }

            currentAnimatedView?.apply {
                updateAnimatedViewBoundsForAmount(0.1f, this)
            }
        } else {
            // We are animating away
            currentAnimatedView!!.view.apply {
                alpha = 1f
            }
        }
    }

    override fun onChipAnimationEnd(@SystemAnimationState state: Int) {
        if (state == ANIMATING_IN) {
            // Finished animating in
            currentAnimatedView?.apply {
                updateAnimatedViewBoundsForAmount(1f, this)
            }
        } else {
            // Finished animating away
            currentAnimatedView!!.view.apply {
                visibility = View.INVISIBLE
            }
            animationWindowView.removeView(currentAnimatedView!!.view)
        }
    }

    override fun onChipAnimationUpdate(
        animator: ValueAnimator,
        @SystemAnimationState state: Int
    ) {
        currentAnimatedView?.apply {
            val amt = (animator.animatedValue as Float).amt()
            view.alpha = (animator.animatedValue as Float)
            updateAnimatedViewBoundsForAmount(amt, this)
        }
    }

    private fun init() {
        initialized = true
        animationWindowView = LayoutInflater.from(context)
                .inflate(R.layout.system_event_animation_window, null) as FrameLayout
        val lp = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        statusBarWindowController.addViewToWindow(animationWindowView, lp)
        animationWindowView.clipToPadding = false
        animationWindowView.clipChildren = false
        animationWindowView.measureAllChildren = true
    }

    private fun layoutParamsDefault(marginEnd: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                it.marginEnd = marginEnd
            }

    private fun updateAnimatedViewBoundsForAmount(amt: Float, chip: BackgroundAnimatableView) {
        when (animationDirection) {
            LEFT -> {
                chip.setBoundsForAnimation(
                        (chipRight - (chipWidth * amt)).toInt(),
                        chip.view.top,
                        chipRight,
                        chip.view.bottom)
            }
            else /* RIGHT */ -> {
                chip.setBoundsForAnimation(
                        chipLeft,
                        chip.view.top,
                        (chipLeft + (chipWidth * amt)).toInt(),
                        chip.view.bottom)
            }
        }
    }

    private fun start() = if (animationWindowView.isLayoutRtl) right() else left()
    private fun right() = contentInsetsProvider.getStatusBarContentAreaForCurrentRotation().right
    private fun left() = contentInsetsProvider.getStatusBarContentAreaForCurrentRotation().left
    private fun Float.amt() = 0.01f.coerceAtLeast(this)
}

/**
 * Chips should provide a view that can be animated with something better than a fade-in
 */
interface BackgroundAnimatableView {
    val view: View // Since this can't extend View, add a view prop
        get() = this as View
    val chipWidth: Int
        get() = view.measuredWidth
    fun setBoundsForAnimation(l: Int, t: Int, r: Int, b: Int)
}

// Animation directions
private const val LEFT = 1
private const val RIGHT = 2
