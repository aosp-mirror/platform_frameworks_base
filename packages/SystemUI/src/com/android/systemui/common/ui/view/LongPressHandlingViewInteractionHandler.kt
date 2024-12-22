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
 *
 */

package com.android.systemui.common.ui.view

import com.android.systemui.log.LongPressHandlingViewLogger
import kotlinx.coroutines.DisposableHandle

/** Encapsulates logic to handle complex touch interactions with a [LongPressHandlingView]. */
class LongPressHandlingViewInteractionHandler(
    /**
     * Callback to run the given [Runnable] with the given delay, returning a [DisposableHandle]
     * allowing the delayed runnable to be canceled before it is run.
     */
    private val postDelayed: (block: Runnable, delayMs: Long) -> DisposableHandle,
    /** Callback to be queried to check if the view is attached to its window. */
    private val isAttachedToWindow: () -> Boolean,
    /** Callback reporting the a long-press gesture was detected at the given coordinates. */
    private val onLongPressDetected: (x: Int, y: Int) -> Unit,
    /** Callback reporting the a single tap gesture was detected at the given coordinates. */
    private val onSingleTapDetected: () -> Unit,
    /** Time for the touch to be considered a long-press in ms */
    var longPressDuration: () -> Long,
    /**
     * Default touch slop that is allowed, if the movement between [MotionEventModel.Down] and
     * [MotionEventModel.Up] is more than [allowedTouchSlop] then the touch is not processed as
     * single tap or a long press.
     */
    val allowedTouchSlop: Int,
    /** Optional logger that can be passed in to log touch events */
    val logger: LongPressHandlingViewLogger? = null,
) {
    sealed class MotionEventModel {
        object Other : MotionEventModel()

        data class Down(
            val x: Int,
            val y: Int,
        ) : MotionEventModel()

        data class Move(
            val distanceMoved: Float,
        ) : MotionEventModel()

        data class Up(
            val distanceMoved: Float,
            val gestureDuration: Long,
        ) : MotionEventModel()

        object Cancel : MotionEventModel()
    }

    var isLongPressHandlingEnabled: Boolean = false
    var scheduledLongPressHandle: DisposableHandle? = null

    fun onTouchEvent(event: MotionEventModel?): Boolean {
        if (!isLongPressHandlingEnabled) {
            return false
        }

        return when (event) {
            is MotionEventModel.Down -> {
                scheduleLongPress(event.x, event.y)
                true
            }
            is MotionEventModel.Move -> {
                if (event.distanceMoved > allowedTouchSlop) {
                    logger?.cancelingLongPressDueToTouchSlop(event.distanceMoved, allowedTouchSlop)
                    cancelScheduledLongPress()
                }
                false
            }
            is MotionEventModel.Up -> {
                logger?.onUpEvent(event.distanceMoved, allowedTouchSlop, event.gestureDuration)
                cancelScheduledLongPress()
                if (
                    event.distanceMoved <= allowedTouchSlop &&
                        event.gestureDuration < longPressDuration()
                ) {
                    logger?.dispatchingSingleTap()
                    dispatchSingleTap()
                }
                false
            }
            is MotionEventModel.Cancel -> {
                logger?.motionEventCancelled()
                cancelScheduledLongPress()
                false
            }
            else -> false
        }
    }

    private fun scheduleLongPress(
        x: Int,
        y: Int,
    ) {
        val duration = longPressDuration()
        logger?.schedulingLongPress(duration)
        scheduledLongPressHandle =
            postDelayed(
                {
                    logger?.longPressTriggered()
                    dispatchLongPress(
                        x = x,
                        y = y,
                    )
                },
                duration,
            )
    }

    private fun dispatchLongPress(
        x: Int,
        y: Int,
    ) {
        if (!isAttachedToWindow()) {
            return
        }

        onLongPressDetected(x, y)
    }

    private fun cancelScheduledLongPress() {
        scheduledLongPressHandle?.dispose()
    }

    private fun dispatchSingleTap() {
        if (!isAttachedToWindow()) {
            return
        }

        onSingleTapDetected()
    }
}
