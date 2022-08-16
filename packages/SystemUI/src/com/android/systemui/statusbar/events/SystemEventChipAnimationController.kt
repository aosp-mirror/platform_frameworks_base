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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.view.ContextThemeWrapper
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
import com.android.systemui.util.animation.AnimationUtil.Companion.frames
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Controls the view for system event animations.
 */
class SystemEventChipAnimationController @Inject constructor(
    private val context: Context,
    private val statusBarWindowController: StatusBarWindowController,
    private val contentInsetsProvider: StatusBarContentInsetsProvider
) : SystemStatusAnimationCallback {

    private lateinit var animationWindowView: FrameLayout
    private lateinit var themedContext: ContextThemeWrapper

    private var currentAnimatedView: BackgroundAnimatableView? = null

    // Left for LTR, Right for RTL
    private var animationDirection = LEFT
    private var chipRight = 0
    private var chipLeft = 0
    private var chipWidth = 0
    private var chipMinWidth = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_chip_min_animation_width)
    private var dotSize = context.resources.getDimensionPixelSize(
            R.dimen.ongoing_appops_dot_diameter)
    // Use during animation so that multiple animators can update the drawing rect
    private var animRect = Rect()

    // TODO: move to dagger
    private var initialized = false

    /**
     * Give the chip controller a chance to inflate and configure the chip view before we start
     * animating
     */
    fun prepareChipAnimation(viewCreator: ViewCreator) {
        if (!initialized) {
            init()
        }
        animationDirection = if (animationWindowView.isLayoutRtl) RIGHT else LEFT

        // Initialize the animated view
        val insets = contentInsetsProvider.getStatusBarContentInsetsForCurrentRotation()
        currentAnimatedView = viewCreator(themedContext).also {
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
    }

    override fun onSystemEventAnimationBegin(): Animator {
        initializeAnimRect()

        val alphaIn = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 7.frames
            duration = 5.frames
            interpolator = null
            addUpdateListener { currentAnimatedView?.view?.alpha = animatedValue as Float }
        }
        val moveIn = ValueAnimator.ofInt(chipMinWidth, chipWidth).apply {
            startDelay = 7.frames
            duration = 23.frames
            interpolator = STATUS_BAR_X_MOVE_IN
            addUpdateListener {
                updateAnimatedViewBoundsWidth(animatedValue as Int)
            }
        }
        val animSet = AnimatorSet()
        animSet.playTogether(alphaIn, moveIn)
        return animSet
    }

    override fun onSystemEventAnimationFinish(hasPersistentDot: Boolean): Animator {
        initializeAnimRect()
        val finish = if (hasPersistentDot) {
            createMoveOutAnimationForDot()
        } else {
            createMoveOutAnimationDefault()
        }

        finish.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                animationWindowView.removeView(currentAnimatedView!!.view)
            }
        })

        return finish
    }

    private fun createMoveOutAnimationForDot(): Animator {
        val width1 = ValueAnimator.ofInt(chipWidth, chipMinWidth).apply {
            duration = 9.frames
            interpolator = STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_1
            addUpdateListener {
                updateAnimatedViewBoundsWidth(it.animatedValue as Int)
            }
        }

        val width2 = ValueAnimator.ofInt(chipMinWidth, dotSize).apply {
            startDelay = 9.frames
            duration = 20.frames
            interpolator = STATUS_CHIP_WIDTH_TO_DOT_KEYFRAME_2
            addUpdateListener {
                updateAnimatedViewBoundsWidth(it.animatedValue as Int)
            }
        }

        val keyFrame1Height = dotSize * 2
        val v = currentAnimatedView!!.view
        val chipVerticalCenter = v.top + v.measuredHeight / 2
        val height1 = ValueAnimator.ofInt(
                currentAnimatedView!!.view.measuredHeight, keyFrame1Height).apply {
            startDelay = 8.frames
            duration = 6.frames
            interpolator = STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_1
            addUpdateListener {
                updateAnimatedViewBoundsHeight(it.animatedValue as Int, chipVerticalCenter)
            }
        }

        val height2 = ValueAnimator.ofInt(keyFrame1Height, dotSize).apply {
            startDelay = 14.frames
            duration = 15.frames
            interpolator = STATUS_CHIP_HEIGHT_TO_DOT_KEYFRAME_2
            addUpdateListener {
                updateAnimatedViewBoundsHeight(it.animatedValue as Int, chipVerticalCenter)
            }
        }

        // Move the chip view to overlap exactly with the privacy dot. The chip displays by default
        // exactly adjacent to the dot, so we can just move over by the diameter of the dot itself
        val moveOut = ValueAnimator.ofInt(0, dotSize).apply {
            startDelay = 3.frames
            duration = 11.frames
            interpolator = STATUS_CHIP_MOVE_TO_DOT
            addUpdateListener {
                // If RTL, we can just invert the move
                val amt = if (animationDirection == LEFT) {
                        animatedValue as Int
                } else {
                    -(animatedValue as Int)
                }
                updateAnimatedBoundsX(amt)
            }
        }

        val animSet = AnimatorSet()
        animSet.playTogether(width1, width2, height1, height2, moveOut)
        return animSet
    }

    private fun createMoveOutAnimationDefault(): Animator {
        val moveOut = ValueAnimator.ofInt(chipWidth, chipMinWidth).apply {
            duration = 23.frames
            addUpdateListener {
                currentAnimatedView?.apply {
                    updateAnimatedViewBoundsWidth(it.animatedValue as Int)
                }
            }
        }
        return moveOut
    }

    private fun init() {
        initialized = true
        themedContext = ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings)
        animationWindowView = LayoutInflater.from(themedContext)
                .inflate(R.layout.system_event_animation_window, null) as FrameLayout
        val lp = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        lp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        statusBarWindowController.addViewToWindow(animationWindowView, lp)
        animationWindowView.clipToPadding = false
        animationWindowView.clipChildren = false
    }

    private fun layoutParamsDefault(marginEnd: Int): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
                it.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                it.marginEnd = marginEnd
            }

    private fun initializeAnimRect() = animRect.set(
            chipLeft,
            currentAnimatedView!!.view.top,
            chipRight,
            currentAnimatedView!!.view.bottom)

    /**
     * To be called during an animation, sets the width and updates the current animated chip view
     */
    private fun updateAnimatedViewBoundsWidth(width: Int) {
        when (animationDirection) {
            LEFT -> {
                animRect.set((chipRight - width), animRect.top, chipRight, animRect.bottom)
            } else /* RIGHT */ -> {
                animRect.set(chipLeft, animRect.top, (chipLeft + width), animRect.bottom)
            }
        }

        updateCurrentAnimatedView()
    }

    /**
     * To be called during an animation, updates the animation rect and sends the update to the chip
     */
    private fun updateAnimatedViewBoundsHeight(height: Int, verticalCenter: Int) {
        animRect.set(
                animRect.left,
                verticalCenter - (height.toFloat() / 2).roundToInt(),
                animRect.right,
                verticalCenter + (height.toFloat() / 2).roundToInt())

        updateCurrentAnimatedView()
    }

    /**
     * To be called during an animation, updates the animation rect offset and updates the chip
     */
    private fun updateAnimatedBoundsX(translation: Int) {
        currentAnimatedView?.view?.translationX = translation.toFloat()
    }

    /**
     * To be called during an animation. Sets the chip rect to animRect
     */
    private fun updateCurrentAnimatedView() {
        currentAnimatedView?.setBoundsForAnimation(
                animRect.left, animRect.top, animRect.right, animRect.bottom
        )
    }
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
