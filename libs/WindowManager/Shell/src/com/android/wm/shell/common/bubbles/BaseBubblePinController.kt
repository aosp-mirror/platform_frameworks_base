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

package com.android.wm.shell.common.bubbles

import android.graphics.RectF
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.core.animation.Animator
import androidx.core.animation.AnimatorListenerAdapter
import androidx.core.animation.ObjectAnimator
import com.android.wm.shell.common.bubbles.BaseBubblePinController.LocationChangeListener
import com.android.wm.shell.common.bubbles.BubbleBarLocation.LEFT
import com.android.wm.shell.common.bubbles.BubbleBarLocation.RIGHT

/**
 * Base class for common logic shared between different bubble views to support pinning bubble bar
 * to left or right edge of screen.
 *
 * Handles drag events and allows a [LocationChangeListener] to be registered that is notified when
 * location of the bubble bar should change.
 *
 * Shows a drop target when releasing a view would update the [BubbleBarLocation].
 */
abstract class BaseBubblePinController {

    private var onLeft = false
    private var dismissZone: RectF? = null
    private var screenCenterX = 0
    private var listener: LocationChangeListener? = null
    private var dropTargetAnimator: ObjectAnimator? = null

    /**
     * Signal the controller that dragging interaction has started.
     *
     * @param initialLocationOnLeft side of the screen where bubble bar is pinned to
     */
    fun onDragStart(initialLocationOnLeft: Boolean) {
        onLeft = initialLocationOnLeft
        dismissZone = getExclusionRect()
        screenCenterX = getScreenCenterX()
    }

    /** View has moved to [x] and [y] screen coordinates */
    fun onDragUpdate(x: Float, y: Float) {
        if (dismissZone?.contains(x, y) == true) return

        if (onLeft && x > screenCenterX) {
            onLeft = false
            onLocationChange(RIGHT)
        } else if (!onLeft && x < screenCenterX) {
            onLeft = true
            onLocationChange(LEFT)
        }
    }

    /** Temporarily hide the drop target view */
    fun setDropTargetHidden(hidden: Boolean) {
        val targetView = getDropTargetView() ?: return
        if (hidden) {
            targetView.animateOut()
        } else {
            targetView.animateIn()
        }
    }

    /** Signal the controller that dragging interaction has finished. */
    fun onDragEnd() {
        getDropTargetView()?.let { view -> view.animateOut { removeDropTargetView(view) } }
        dismissZone = null
    }

    /**
     * [LocationChangeListener] that is notified when dragging interaction has resulted in bubble
     * bar to be pinned on the other edge
     */
    fun setListener(listener: LocationChangeListener?) {
        this.listener = listener
    }

    /** Get screen center coordinate on the x axis. */
    protected abstract fun getScreenCenterX(): Int

    /** Optional exclusion rect where drag interactions are not processed */
    protected abstract fun getExclusionRect(): RectF?

    /** Create the drop target view and attach it to the parent */
    protected abstract fun createDropTargetView(): View

    /** Get the drop target view if it exists */
    protected abstract fun getDropTargetView(): View?

    /** Remove the drop target view */
    protected abstract fun removeDropTargetView(view: View)

    /** Update size and location of the drop target view */
    protected abstract fun updateLocation(location: BubbleBarLocation)

    private fun onLocationChange(location: BubbleBarLocation) {
        showDropTarget(location)
        listener?.onChange(location)
    }

    private fun showDropTarget(location: BubbleBarLocation) {
        val targetView = getDropTargetView() ?: createDropTargetView().apply { alpha = 0f }
        if (targetView.alpha > 0) {
            targetView.animateOut {
                updateLocation(location)
                targetView.animateIn()
            }
        } else {
            updateLocation(location)
            targetView.animateIn()
        }
    }

    private fun View.animateIn() {
        dropTargetAnimator?.cancel()
        dropTargetAnimator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 1f)
                .setDuration(DROP_TARGET_ALPHA_IN_DURATION)
                .addEndAction { dropTargetAnimator = null }
        dropTargetAnimator?.start()
    }

    private fun View.animateOut(endAction: Runnable? = null) {
        dropTargetAnimator?.cancel()
        dropTargetAnimator =
            ObjectAnimator.ofFloat(this, View.ALPHA, 0f)
                .setDuration(DROP_TARGET_ALPHA_OUT_DURATION)
                .addEndAction {
                    endAction?.run()
                    dropTargetAnimator = null
                }
        dropTargetAnimator?.start()
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

    /** Receive updates on location changes */
    interface LocationChangeListener {
        /**
         * Bubble bar [BubbleBarLocation] has changed as a result of dragging
         *
         * Triggered when drag gesture passes the middle of the screen and before touch up. Can be
         * triggered multiple times per gesture.
         *
         * @param location new location as a result of the ongoing drag operation
         */
        fun onChange(location: BubbleBarLocation)
    }

    companion object {
        @VisibleForTesting const val DROP_TARGET_ALPHA_IN_DURATION = 150L
        @VisibleForTesting const val DROP_TARGET_ALPHA_OUT_DURATION = 100L
    }
}
