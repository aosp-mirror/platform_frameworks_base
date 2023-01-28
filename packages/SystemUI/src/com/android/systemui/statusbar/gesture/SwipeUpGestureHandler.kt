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

package com.android.systemui.statusbar.gesture

import android.content.Context
import android.view.InputEvent
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.settings.DisplayTracker

/**
 * A class to detect a generic "swipe up" gesture. To be notified when the swipe up gesture is
 * detected, add a callback via [addOnGestureDetectedCallback].
 */
@SysUISingleton
abstract class SwipeUpGestureHandler(
    context: Context,
    displayTracker: DisplayTracker,
    private val logger: SwipeUpGestureLogger,
    private val loggerTag: String
) : GenericGestureDetector(SwipeUpGestureHandler::class.simpleName!!, displayTracker) {

    private var startY: Float = 0f
    private var startTime: Long = 0L
    private var monitoringCurrentTouch: Boolean = false

    private var swipeDistanceThreshold: Int = context.resources.getDimensionPixelSize(
        com.android.internal.R.dimen.system_gestures_start_threshold
    )

    override fun onInputEvent(ev: InputEvent) {
        if (ev !is MotionEvent) {
            return
        }

        when (ev.actionMasked) {
            ACTION_DOWN -> {
                if (
                    startOfGestureIsWithinBounds(ev)
                ) {
                    logger.logGestureDetectionStarted(loggerTag, ev.y.toInt())
                    startY = ev.y
                    startTime = ev.eventTime
                    monitoringCurrentTouch = true
                } else {
                    monitoringCurrentTouch = false
                }
            }
            ACTION_MOVE -> {
                if (!monitoringCurrentTouch) {
                    return
                }
                if (
                    // Gesture is up
                    ev.y < startY &&
                    // Gesture went far enough
                    (startY - ev.y) >= swipeDistanceThreshold &&
                    // Gesture completed quickly enough
                    (ev.eventTime - startTime) < SWIPE_TIMEOUT_MS
                ) {
                    monitoringCurrentTouch = false
                    logger.logGestureDetected(loggerTag, ev.y.toInt())
                    onGestureDetected(ev)
                }
            }
            ACTION_CANCEL, ACTION_UP -> {
                if (monitoringCurrentTouch) {
                    logger.logGestureDetectionEndedWithoutTriggering(loggerTag, ev.y.toInt())
                }
                monitoringCurrentTouch = false
            }
        }
    }

    /**
     * Returns true if the [ACTION_DOWN] event falls within bounds for this specific swipe-up
     * gesture.
     *
     * Implementations must override this method to specify what part(s) of the screen are valid
     * locations for the swipe up gesture to start at.
     */
    abstract fun startOfGestureIsWithinBounds(ev: MotionEvent): Boolean

    override fun startGestureListening() {
        super.startGestureListening()
        logger.logInputListeningStarted(loggerTag)
    }

    override fun stopGestureListening() {
        super.stopGestureListening()
        logger.logInputListeningStopped(loggerTag)
    }
}

private const val SWIPE_TIMEOUT_MS: Long = 500
