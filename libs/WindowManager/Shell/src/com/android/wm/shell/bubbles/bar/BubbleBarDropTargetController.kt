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
package com.android.wm.shell.bubbles.bar

import android.content.Context
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.FrameLayout.LayoutParams
import androidx.annotation.VisibleForTesting
import androidx.core.animation.Animator
import androidx.core.animation.AnimatorListenerAdapter
import androidx.core.animation.ObjectAnimator
import com.android.wm.shell.R
import com.android.wm.shell.bubbles.BubblePositioner
import com.android.wm.shell.common.bubbles.BubbleBarLocation

/** Controller to show/hide drop target when bubble bar expanded view is being dragged */
class BubbleBarDropTargetController(
    val context: Context,
    val container: FrameLayout,
    val positioner: BubblePositioner
) {

    private var dropTargetView: View? = null
    private var animator: ObjectAnimator? = null
    private val tempRect: Rect by lazy(LazyThreadSafetyMode.NONE) { Rect() }

    /**
     * Show drop target at [location] with animation.
     *
     * If the drop target is currently visible, animates it out first, before showing it at the
     * supplied location.
     */
    fun show(location: BubbleBarLocation) {
        val targetView = dropTargetView ?: createView().also { dropTargetView = it }
        if (targetView.alpha > 0) {
            targetView.animateOut {
                targetView.updateBounds(location)
                targetView.animateIn()
            }
        } else {
            targetView.updateBounds(location)
            targetView.animateIn()
        }
    }

    /**
     * Set the view hidden or not
     *
     * Requires the drop target to be first shown by calling [animateIn]. Otherwise does not do
     * anything.
     */
    fun setHidden(hidden: Boolean) {
        val targetView = dropTargetView ?: return
        if (hidden) {
            targetView.animateOut()
        } else {
            targetView.animateIn()
        }
    }

    /** Remove the drop target if it is was shown. */
    fun dismiss() {
        dropTargetView?.animateOut {
            dropTargetView?.let { container.removeView(it) }
            dropTargetView = null
        }
    }

    private fun createView(): View {
        return LayoutInflater.from(context)
            .inflate(R.layout.bubble_bar_drop_target, container, false /* attachToRoot */)
            .also { view: View ->
                view.alpha = 0f
                // Add at index 0 to ensure it does not cover the bubble
                container.addView(view, 0)
            }
    }

    private fun getBounds(onLeft: Boolean, out: Rect) {
        positioner.getBubbleBarExpandedViewBounds(onLeft, false /* isOverflowExpanded */, out)
        val centerX = out.centerX()
        val centerY = out.centerY()
        out.scale(DROP_TARGET_SCALE)
        // Move rect center back to the same position as before scale
        out.offset(centerX - out.centerX(), centerY - out.centerY())
    }

    private fun View.updateBounds(location: BubbleBarLocation) {
        getBounds(location.isOnLeft(isLayoutRtl), tempRect)
        val lp = layoutParams as LayoutParams
        lp.width = tempRect.width()
        lp.height = tempRect.height()
        layoutParams = lp
        x = tempRect.left.toFloat()
        y = tempRect.top.toFloat()
    }

    private fun View.animateIn() {
        animator?.cancel()
        animator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 1f)
                .setDuration(DROP_TARGET_ALPHA_IN_DURATION)
                .addEndAction { animator = null }
        animator?.start()
    }

    private fun View.animateOut(endAction: Runnable? = null) {
        animator?.cancel()
        animator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 0f)
                .setDuration(DROP_TARGET_ALPHA_OUT_DURATION)
                .addEndAction {
                    endAction?.run()
                    animator = null
                }
        animator?.start()
    }

    private fun <T : Animator> T.addEndAction(runnable: Runnable): T {
        addListener(
            object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runnable.run()
                }
            }
        )
        return this
    }

    companion object {
        @VisibleForTesting const val DROP_TARGET_ALPHA_IN_DURATION = 150L
        @VisibleForTesting const val DROP_TARGET_ALPHA_OUT_DURATION = 100L
        @VisibleForTesting const val DROP_TARGET_SCALE = 0.9f
    }
}
