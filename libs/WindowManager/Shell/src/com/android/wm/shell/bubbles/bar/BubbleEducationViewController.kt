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
package com.android.wm.shell.bubbles.bar

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.doOnLayout
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.android.wm.shell.R
import com.android.wm.shell.animation.PhysicsAnimator
import com.android.wm.shell.bubbles.BubbleEducationController
import com.android.wm.shell.bubbles.BubbleViewProvider
import com.android.wm.shell.bubbles.setup
import com.android.wm.shell.common.bubbles.BubblePopupView

/** Manages bubble education presentation and animation */
class BubbleEducationViewController(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onManageEducationVisibilityChanged(isVisible: Boolean)
    }

    private var rootView: ViewGroup? = null
    private var educationView: BubblePopupView? = null
    private var animator: PhysicsAnimator<BubblePopupView>? = null

    private val springConfig by lazy {
        PhysicsAnimator.SpringConfig(
            SpringForce.STIFFNESS_MEDIUM,
            SpringForce.DAMPING_RATIO_LOW_BOUNCY
        )
    }

    private val controller by lazy { BubbleEducationController(context) }

    /** Whether the education view is visible or being animated */
    val isManageEducationVisible: Boolean
        get() = educationView != null && rootView != null

    /**
     * Show manage bubble education if hasn't been shown before
     *
     * @param bubble the bubble used for the manage education check
     * @param root the view to show manage education in
     */
    fun maybeShowManageEducation(bubble: BubbleViewProvider, root: ViewGroup) {
        if (!controller.shouldShowManageEducation(bubble)) return
        showManageEducation(root)
    }

    /**
     * Hide the manage education view if visible
     *
     * @param animated whether should hide with animation
     */
    fun hideManageEducation(animated: Boolean) {
        rootView?.let {
            fun cleanUp() {
                it.removeView(educationView)
                rootView = null
                listener.onManageEducationVisibilityChanged(isVisible = false)
            }

            if (animated) {
                animateTransition(show = false, ::cleanUp)
            } else {
                cleanUp()
            }
        }
    }

    /**
     * Show manage education with animation
     *
     * @param root the view to show manage education in
     */
    private fun showManageEducation(root: ViewGroup) {
        hideManageEducation(animated = false)
        if (educationView == null) {
            val eduView = createEducationView(root)
            educationView = eduView
            animator = createAnimation(eduView)
        }
        root.addView(educationView)
        rootView = root
        animateTransition(show = true) {
            controller.hasSeenManageEducation = true
            listener.onManageEducationVisibilityChanged(isVisible = true)
        }
    }

    /**
     * Animate show/hide transition for the education view
     *
     * @param show whether to show or hide the view
     * @param endActions a closure to be called when the animation completes
     */
    private fun animateTransition(show: Boolean, endActions: () -> Unit) {
        animator?.let { animator ->
            animator
                .spring(DynamicAnimation.ALPHA, if (show) 1f else 0f)
                .spring(DynamicAnimation.SCALE_X, if (show) 1f else EDU_SCALE_HIDDEN)
                .spring(DynamicAnimation.SCALE_Y, if (show) 1f else EDU_SCALE_HIDDEN)
                .withEndActions(endActions)
                .start()
        } ?: endActions()
    }

    private fun createEducationView(root: ViewGroup): BubblePopupView {
        val view =
            LayoutInflater.from(context).inflate(R.layout.bubble_bar_manage_education, root, false)
                as BubblePopupView

        return view.apply {
            setup()
            alpha = 0f
            pivotY = 0f
            scaleX = EDU_SCALE_HIDDEN
            scaleY = EDU_SCALE_HIDDEN
            doOnLayout { it.pivotX = it.width / 2f }
            setOnClickListener { hideManageEducation(animated = true) }
        }
    }

    private fun createAnimation(view: BubblePopupView): PhysicsAnimator<BubblePopupView> {
        val animator = PhysicsAnimator.getInstance(view)
        animator.setDefaultSpringConfig(springConfig)
        return animator
    }

    companion object {
        private const val EDU_SCALE_HIDDEN = 0.5f
    }
}
