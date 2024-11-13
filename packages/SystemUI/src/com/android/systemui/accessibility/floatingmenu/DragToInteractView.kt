/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.ArrayMap
import android.util.IntProperty
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Space
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce.DAMPING_RATIO_LOW_BOUNCY
import androidx.dynamicanimation.animation.SpringForce.STIFFNESS_LOW
import com.android.wm.shell.R
import com.android.wm.shell.shared.animation.PhysicsAnimator
import com.android.wm.shell.shared.bubbles.DismissCircleView
import com.android.wm.shell.shared.bubbles.DismissView

/**
 * View that handles interactions between DismissCircleView and BubbleStackView.
 *
 * @note [setup] method should be called after initialisation
 */
class DragToInteractView(context: Context) : FrameLayout(context) {
    /**
     * The configuration is used to provide module specific resource ids
     *
     * @see [setup] method
     */
    data class Config(
        /** dimen resource id of the dismiss target circle view size */
        @DimenRes val targetSizeResId: Int,
        /** dimen resource id of the icon size in the dismiss target */
        @DimenRes val iconSizeResId: Int,
        /** dimen resource id of the bottom margin for the dismiss target */
        @DimenRes var bottomMarginResId: Int,
        /** dimen resource id of the height for dismiss area gradient */
        @DimenRes val floatingGradientHeightResId: Int,
        /** color resource id of the dismiss area gradient color */
        @ColorRes val floatingGradientColorResId: Int,
        /** drawable resource id of the dismiss target background */
        @DrawableRes val backgroundResId: Int,
        /** drawable resource id of the icon for the dismiss target */
        @DrawableRes val iconResId: Int
    )

    companion object {
        private const val SHOULD_SETUP = "The view isn't ready. Should be called after `setup`"
        private val TAG = DragToInteractView::class.simpleName
    }

    // START DragToInteractView modification
    // We could technically access each DismissCircleView from their Animator,
    // but the animators only store a weak reference to their targets. This is safer.
    var interactMap = ArrayMap<Int, Pair<DismissCircleView, PhysicsAnimator<DismissCircleView>>>()
    // END DragToInteractView modification
    var isShowing = false
    var config: Config? = null

    private val spring = PhysicsAnimator.SpringConfig(STIFFNESS_LOW, DAMPING_RATIO_LOW_BOUNCY)
    private val INTERACT_SCRIM_FADE_MS = 200L
    private var wm: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var gradientDrawable: GradientDrawable? = null

    private val GRADIENT_ALPHA: IntProperty<GradientDrawable> =
        object : IntProperty<GradientDrawable>("alpha") {
            override fun setValue(d: GradientDrawable, percent: Int) {
                d.alpha = percent
            }
            override fun get(d: GradientDrawable): Int {
                return d.alpha
            }
        }

    init {
        clipToPadding = false
        clipChildren = false
        visibility = View.INVISIBLE

        // START DragToInteractView modification
        // Resources included within implementation as we aren't concerned with decoupling them.
        setup(
            Config(
                targetSizeResId = R.dimen.dismiss_circle_size,
                iconSizeResId = R.dimen.dismiss_target_x_size,
                bottomMarginResId = R.dimen.floating_dismiss_bottom_margin,
                floatingGradientHeightResId = R.dimen.floating_dismiss_gradient_height,
                floatingGradientColorResId = android.R.color.system_neutral1_900,
                backgroundResId = R.drawable.dismiss_circle_background,
                iconResId = R.drawable.pip_ic_close_white
            )
        )

        // Ensure this is unfocusable & uninteractable
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO

        // END DragToInteractView modification
    }

    /**
     * Sets up view with the provided resource ids.
     *
     * Decouples resource dependency in order to be used externally (e.g. Launcher). Usually called
     * with default params in module specific extension:
     *
     * @see [DismissView.setup] in DismissViewExt.kt
     */
    fun setup(config: Config) {
        this.config = config

        // Setup layout
        layoutParams =
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(config.floatingGradientHeightResId),
                Gravity.BOTTOM
            )
        updatePadding()

        // Setup gradient
        gradientDrawable = createGradient(color = config.floatingGradientColorResId)
        background = gradientDrawable

        // START DragToInteractView modification

        // Setup LinearLayout. Added to organize multiple circles.
        val linearLayout = LinearLayout(context)
        linearLayout.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        linearLayout.weightSum = 0f
        addView(linearLayout)

        // Setup DismissCircleView. Code block replaced with repeatable functions
        addSpace(linearLayout)
        addCircle(
            config,
            com.android.systemui.res.R.id.action_remove_menu,
            R.drawable.pip_ic_close_white,
            linearLayout
        )
        addCircle(
            config,
            com.android.systemui.res.R.id.action_edit,
            com.android.systemui.res.R.drawable.ic_screenshot_edit,
            linearLayout
        )
        // END DragToInteractView modification
    }

    /** Animates this view in. */
    fun show() {
        if (isShowing) return
        val gradientDrawable = checkExists(gradientDrawable) ?: return
        isShowing = true
        visibility = View.VISIBLE
        val alphaAnim =
            ObjectAnimator.ofInt(gradientDrawable, GRADIENT_ALPHA, gradientDrawable.alpha, 255)
        alphaAnim.duration = INTERACT_SCRIM_FADE_MS
        alphaAnim.start()

        // START DragToInteractView modification
        interactMap.forEach {
            val animator = it.value.second
            animator.cancel()
            animator.spring(DynamicAnimation.TRANSLATION_Y, 0f, spring).start()
        }
        // END DragToInteractView modification
    }

    /**
     * Animates this view out, as well as the circle that encircles the bubbles, if they were
     * dragged into the target and encircled.
     */
    fun hide() {
        if (!isShowing) return
        val gradientDrawable = checkExists(gradientDrawable) ?: return
        isShowing = false
        val alphaAnim =
            ObjectAnimator.ofInt(gradientDrawable, GRADIENT_ALPHA, gradientDrawable.alpha, 0)
        alphaAnim.duration = INTERACT_SCRIM_FADE_MS
        alphaAnim.start()

        // START DragToInteractView modification
        interactMap.forEach {
            val animator = it.value.second
            animator
                .spring(DynamicAnimation.TRANSLATION_Y, height.toFloat(), spring)
                .withEndActions({ visibility = View.INVISIBLE })
                .start()
        }
        // END DragToInteractView modification
    }

    /** Cancels the animator for the dismiss target. */
    fun cancelAnimators() {
        // START DragToInteractView modification
        interactMap.forEach {
            val animator = it.value.second
            animator.cancel()
        }
        // END DragToInteractView modification
    }

    fun updateResources() {
        val config = checkExists(config) ?: return
        updatePadding()
        layoutParams.height = resources.getDimensionPixelSize(config.floatingGradientHeightResId)
        val targetSize = resources.getDimensionPixelSize(config.targetSizeResId)

        // START DragToInteractView modification
        interactMap.forEach {
            val circle = it.value.first
            circle.layoutParams.width = targetSize
            circle.layoutParams.height = targetSize
            circle.requestLayout()
        }
        // END DragToInteractView modification
    }

    private fun createGradient(@ColorRes color: Int): GradientDrawable {
        val gradientColor = ContextCompat.getColor(context, color)
        val alpha = 0.7f * 255
        val gradientColorWithAlpha =
            Color.argb(
                alpha.toInt(),
                Color.red(gradientColor),
                Color.green(gradientColor),
                Color.blue(gradientColor)
            )
        val gd =
            GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(gradientColorWithAlpha, Color.TRANSPARENT)
            )
        gd.setDither(true)
        gd.alpha = 0
        return gd
    }

    private fun updatePadding() {
        val config = checkExists(config) ?: return
        val insets: WindowInsets = wm.currentWindowMetrics.windowInsets
        val navInset = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars())
        setPadding(
            0,
            0,
            0,
            navInset.bottom + resources.getDimensionPixelSize(config.bottomMarginResId)
        )
    }

    /**
     * Checks if the value is set up and exists, if not logs an exception. Used for convenient
     * logging in case `setup` wasn't called before
     *
     * @return value provided as argument
     */
    private fun <T> checkExists(value: T?): T? {
        if (value == null) Log.e(TAG, SHOULD_SETUP)
        return value
    }

    // START DragToInteractView modification
    private fun addSpace(parent: LinearLayout) {
        val space = Space(context)
        // Spaces are weighted equally to space out circles evenly
        space.layoutParams =
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        parent.addView(space)
        parent.weightSum = parent.weightSum + 1f
    }

    private fun addCircle(config: Config, id: Int, iconResId: Int, parent: LinearLayout) {
        val targetSize = resources.getDimensionPixelSize(config.targetSizeResId)
        val circleLayoutParams = LinearLayout.LayoutParams(targetSize, targetSize, 0f)
        circleLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val circle = DismissCircleView(context)
        circle.id = id
        circle.setup(config.backgroundResId, iconResId, config.iconSizeResId)
        circle.layoutParams = circleLayoutParams

        // Initial position with circle offscreen so it's animated up
        circle.translationY =
            resources.getDimensionPixelSize(config.floatingGradientHeightResId).toFloat()

        interactMap[circle.id] = Pair(circle, PhysicsAnimator.getInstance(circle))
        parent.addView(circle)
        addSpace(parent)
    }
    // END DragToInteractView modification
}
