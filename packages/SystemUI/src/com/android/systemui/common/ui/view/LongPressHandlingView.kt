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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.android.systemui.shade.TouchLogger
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.DisposableHandle

/**
 * View designed to handle long-presses.
 *
 * The view will not handle any long pressed by default. To set it up, set up a listener and, when
 * ready to start consuming long-presses, set [setLongPressHandlingEnabled] to `true`.
 */
class LongPressHandlingView(
    context: Context,
    attrs: AttributeSet?,
    longPressDuration: () -> Long,
) :
    View(
        context,
        attrs,
    ) {

    constructor(
        context: Context,
        attrs: AttributeSet?,
    ) : this(context, attrs, { ViewConfiguration.getLongPressTimeout().toLong() })

    interface Listener {
        /** Notifies that a long-press has been detected by the given view. */
        fun onLongPressDetected(
            view: View,
            x: Int,
            y: Int,
        )

        /** Notifies that the gesture was too short for a long press, it is actually a click. */
        fun onSingleTapDetected(view: View) = Unit
    }

    var listener: Listener? = null

    private val interactionHandler: LongPressHandlingViewInteractionHandler by lazy {
        LongPressHandlingViewInteractionHandler(
            postDelayed = { block, timeoutMs ->
                val dispatchToken = Any()

                handler.postDelayed(
                    block,
                    dispatchToken,
                    timeoutMs,
                )

                DisposableHandle { handler.removeCallbacksAndMessages(dispatchToken) }
            },
            isAttachedToWindow = ::isAttachedToWindow,
            onLongPressDetected = { x, y ->
                listener?.onLongPressDetected(
                    view = this,
                    x = x,
                    y = y,
                )
            },
            onSingleTapDetected = { listener?.onSingleTapDetected(this@LongPressHandlingView) },
            longPressDuration = longPressDuration,
        )
    }

    var longPressDuration: () -> Long
        get() = interactionHandler.longPressDuration
        set(longPressDuration) {
            interactionHandler.longPressDuration = longPressDuration
        }

    fun setLongPressHandlingEnabled(isEnabled: Boolean) {
        interactionHandler.isLongPressHandlingEnabled = isEnabled
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return TouchLogger.logDispatchTouch("long_press", event, super.dispatchTouchEvent(event))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return interactionHandler.onTouchEvent(event?.toModel())
    }
}

private fun MotionEvent.toModel(): LongPressHandlingViewInteractionHandler.MotionEventModel {
    return when (actionMasked) {
        MotionEvent.ACTION_DOWN ->
            LongPressHandlingViewInteractionHandler.MotionEventModel.Down(
                x = x.toInt(),
                y = y.toInt(),
            )
        MotionEvent.ACTION_MOVE ->
            LongPressHandlingViewInteractionHandler.MotionEventModel.Move(
                distanceMoved = distanceMoved(),
            )
        MotionEvent.ACTION_UP ->
            LongPressHandlingViewInteractionHandler.MotionEventModel.Up(
                distanceMoved = distanceMoved(),
                gestureDuration = gestureDuration(),
            )
        MotionEvent.ACTION_CANCEL -> LongPressHandlingViewInteractionHandler.MotionEventModel.Cancel
        else -> LongPressHandlingViewInteractionHandler.MotionEventModel.Other
    }
}

private fun MotionEvent.distanceMoved(): Float {
    return if (historySize > 0) {
        sqrt((x - getHistoricalX(0)).pow(2) + (y - getHistoricalY(0)).pow(2))
    } else {
        0f
    }
}

private fun MotionEvent.gestureDuration(): Long {
    return eventTime - downTime
}
