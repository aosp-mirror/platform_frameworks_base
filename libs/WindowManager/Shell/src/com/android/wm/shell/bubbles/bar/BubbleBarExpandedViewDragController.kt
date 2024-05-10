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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.PointF
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import com.android.wm.shell.animation.Interpolators
import com.android.wm.shell.common.bubbles.DismissView
import com.android.wm.shell.common.bubbles.RelativeTouchListener

/** Controller for handling drag interactions with [BubbleBarExpandedView] */
class BubbleBarExpandedViewDragController(
    private val expandedView: BubbleBarExpandedView,
    private val dismissView: DismissView,
    private val onDismissed: () -> Unit
) {

    init {
        expandedView.handleView.setOnTouchListener(HandleDragListener())
    }

    private fun finishDrag(x: Float, y: Float, viewInitialX: Float, viewInitialY: Float) {
        val dismissCircleBounds = Rect().apply { dismissView.circle.getBoundsOnScreen(this) }
        if (dismissCircleBounds.contains(x.toInt(), y.toInt())) {
            onDismissed()
        } else {
            resetExpandedViewPosition(viewInitialX, viewInitialY)
        }
        dismissView.hide()
    }

    private fun resetExpandedViewPosition(initialX: Float, initialY: Float) {
        val listener =
            object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    expandedView.isAnimating = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    expandedView.isAnimating = false
                }
            }
        expandedView
            .animate()
            .translationX(initialX)
            .translationY(initialY)
            .setDuration(RESET_POSITION_ANIM_DURATION)
            .setInterpolator(Interpolators.EMPHASIZED_DECELERATE)
            .setListener(listener)
            .start()
    }

    private inner class HandleDragListener : RelativeTouchListener() {

        private val expandedViewRestPosition = PointF()

        override fun onDown(v: View, ev: MotionEvent): Boolean {
            // While animating, don't allow new touch events
            if (expandedView.isAnimating) {
                return false
            }
            expandedViewRestPosition.x = expandedView.translationX
            expandedViewRestPosition.y = expandedView.translationY
            return true
        }

        override fun onMove(
            v: View,
            ev: MotionEvent,
            viewInitialX: Float,
            viewInitialY: Float,
            dx: Float,
            dy: Float
        ) {
            expandedView.translationX = expandedViewRestPosition.x + dx
            expandedView.translationY = expandedViewRestPosition.y + dy
            dismissView.show()
        }

        override fun onUp(
            v: View,
            ev: MotionEvent,
            viewInitialX: Float,
            viewInitialY: Float,
            dx: Float,
            dy: Float,
            velX: Float,
            velY: Float
        ) {
            finishDrag(ev.rawX, ev.rawY, expandedViewRestPosition.x, expandedViewRestPosition.y)
        }

        override fun onCancel(v: View, ev: MotionEvent, viewInitialX: Float, viewInitialY: Float) {
            resetExpandedViewPosition(expandedViewRestPosition.x, expandedViewRestPosition.y)
            dismissView.hide()
        }
    }

    companion object {
        const val RESET_POSITION_ANIM_DURATION = 300L
    }
}
