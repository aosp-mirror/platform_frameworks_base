/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles

import android.graphics.PointF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Listener which receives [onDown], [onMove], and [onUp] events, with relevant information about
 * the coordinates of the touch and the view relative to the initial ACTION_DOWN event and the
 * view's initial position.
 */
abstract class RelativeTouchListener : View.OnTouchListener {

    /**
     * Called when an ACTION_DOWN event is received for the given view.
     *
     * @return False if the object is not interested in MotionEvents at this time, or true if we
     * should consume this event and subsequent events, and begin calling [onMove].
     */
    abstract fun onDown(v: View, ev: MotionEvent): Boolean

    /**
     * Called when an ACTION_MOVE event is received for the given view. This signals that the view
     * is being dragged.
     *
     * @param viewInitialX The view's translationX value when this touch gesture started.
     * @param viewInitialY The view's translationY value when this touch gesture started.
     * @param dx Horizontal distance covered since the initial ACTION_DOWN event, in pixels.
     * @param dy Vertical distance covered since the initial ACTION_DOWN event, in pixels.
     */
    abstract fun onMove(
        v: View,
        ev: MotionEvent,
        viewInitialX: Float,
        viewInitialY: Float,
        dx: Float,
        dy: Float
    )

    /**
     * Called when an ACTION_UP event is received for the given view. This signals that a drag or
     * fling gesture has completed.
     *
     * @param viewInitialX The view's translationX value when this touch gesture started.
     * @param viewInitialY The view's translationY value when this touch gesture started.
     * @param dx Horizontal distance covered, in pixels.
     * @param dy Vertical distance covered, in pixels.
     * @param velX The final horizontal velocity of the gesture, in pixels/second.
     * @param velY The final vertical velocity of the gesture, in pixels/second.
     */
    abstract fun onUp(
        v: View,
        ev: MotionEvent,
        viewInitialX: Float,
        viewInitialY: Float,
        dx: Float,
        dy: Float,
        velX: Float,
        velY: Float
    )

    /** The raw coordinates of the last ACTION_DOWN event. */
    private val touchDown = PointF()

    /** The coordinates of the view, at the time of the last ACTION_DOWN event. */
    private val viewPositionOnTouchDown = PointF()

    private val velocityTracker = VelocityTracker.obtain()

    private var touchSlop: Int = -1
    private var movedEnough = false

    private var performedLongClick = false

    @Suppress("UNCHECKED_CAST")
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        addMovement(ev)

        val dx = ev.rawX - touchDown.x
        val dy = ev.rawY - touchDown.y

        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!onDown(v, ev)) {
                    return false
                }

                // Grab the touch slop, it might have changed if the config changed since the
                // last gesture.
                touchSlop = ViewConfiguration.get(v.context).scaledTouchSlop

                touchDown.set(ev.rawX, ev.rawY)
                viewPositionOnTouchDown.set(v.translationX, v.translationY)

                performedLongClick = false
                v.handler.postDelayed({
                    if (v.isLongClickable) {
                        performedLongClick = v.performLongClick()
                    }
                }, ViewConfiguration.getLongPressTimeout().toLong())
            }

            MotionEvent.ACTION_MOVE -> {
                if (!movedEnough && hypot(dx, dy) > touchSlop && !performedLongClick) {
                    movedEnough = true
                    v.handler.removeCallbacksAndMessages(null)
                }

                if (movedEnough) {
                    onMove(v, ev, viewPositionOnTouchDown.x, viewPositionOnTouchDown.y, dx, dy)
                }
            }

            MotionEvent.ACTION_UP -> {
                if (movedEnough) {
                    velocityTracker.computeCurrentVelocity(1000 /* units */)
                    onUp(v, ev, viewPositionOnTouchDown.x, viewPositionOnTouchDown.y, dx, dy,
                            velocityTracker.xVelocity, velocityTracker.yVelocity)
                } else if (!performedLongClick) {
                    v.performClick()
                } else {
                    v.handler.removeCallbacksAndMessages(null)
                }

                velocityTracker.clear()
                movedEnough = false
            }

            MotionEvent.ACTION_CANCEL -> {
                v.handler.removeCallbacksAndMessages(null)
                velocityTracker.clear()
                movedEnough = false
            }
        }

        return true
    }

    /**
     * Adds a movement to the velocity tracker using raw screen coordinates.
     */
    private fun addMovement(event: MotionEvent) {
        val deltaX = event.rawX - event.x
        val deltaY = event.rawY - event.y
        event.offsetLocation(deltaX, deltaY)
        velocityTracker.addMovement(event)
        event.offsetLocation(-deltaX, -deltaY)
    }
}