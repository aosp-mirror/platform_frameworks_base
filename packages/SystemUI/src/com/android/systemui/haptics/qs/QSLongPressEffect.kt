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

package com.android.systemui.haptics.qs

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.VibrationEffect
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.VisibleForTesting
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.animation.doOnStart
import com.android.systemui.statusbar.VibratorHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A class that handles the long press visuo-haptic effect for a QS tile.
 *
 * The class is also a [View.OnTouchListener] to handle the touch events, clicks and long-press
 * gestures of the tile. The class also provides a [State] that can be used to determine the current
 * state of the long press effect.
 *
 * @property[vibratorHelper] The [VibratorHelper] to deliver haptic effects.
 * @property[effectDuration] The duration of the effect in ms.
 */
class QSLongPressEffect(
    private val vibratorHelper: VibratorHelper?,
    private val effectDuration: Int,
) : View.OnTouchListener {

    /** Current state */
    var state = State.IDLE
        @VisibleForTesting set

    /** Flows for view control and action */
    private val _effectProgress = MutableStateFlow<Float?>(null)
    val effectProgress = _effectProgress.asStateFlow()

    private val _actionType = MutableStateFlow<ActionType?>(null)
    val actionType = _actionType.asStateFlow()

    /** Haptic effects */
    private val durations =
        vibratorHelper?.getPrimitiveDurations(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_SPIN
        )

    private val longPressHint =
        LongPressHapticBuilder.createLongPressHint(
            durations?.get(0) ?: LongPressHapticBuilder.INVALID_DURATION,
            durations?.get(1) ?: LongPressHapticBuilder.INVALID_DURATION,
            effectDuration
        )

    private val snapEffect = LongPressHapticBuilder.createSnapEffect()

    /* A coroutine scope and a timer job that waits for the pressedTimeout */
    var scope: CoroutineScope? = null
    private var waitJob: Job? = null

    private val effectAnimator =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = effectDuration.toLong()
            interpolator = AccelerateDecelerateInterpolator()

            doOnStart { handleAnimationStart() }
            addUpdateListener { _effectProgress.value = animatedValue as Float }
            doOnEnd { handleAnimationComplete() }
            doOnCancel { handleAnimationCancel() }
        }

    private fun reverse() {
        val pausedProgress = effectAnimator.animatedFraction
        val effect =
            LongPressHapticBuilder.createReversedEffect(
                pausedProgress,
                durations?.get(0) ?: 0,
                effectDuration,
            )
        vibratorHelper?.cancel()
        vibrate(effect)
        effectAnimator.reverse()
    }

    private fun vibrate(effect: VibrationEffect?) {
        if (vibratorHelper != null && effect != null) {
            vibratorHelper.vibrate(effect)
        }
    }

    /**
     * Handle relevant touch events for the operation of a Tile.
     *
     * A click action is performed following the relevant logic that originates from the
     * [MotionEvent.ACTION_UP] event depending on the current state.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouch(view: View?, event: MotionEvent?): Boolean {
        when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleActionDown()
            MotionEvent.ACTION_UP -> handleActionUp()
            MotionEvent.ACTION_CANCEL -> handleActionCancel()
        }
        return true
    }

    private fun handleActionDown() {
        when (state) {
            State.IDLE -> {
                startPressedTimeoutWait()
                state = State.TIMEOUT_WAIT
            }
            State.RUNNING_BACKWARDS -> effectAnimator.cancel()
            else -> {}
        }
    }

    private fun startPressedTimeoutWait() {
        waitJob =
            scope?.launch {
                try {
                    delay(PRESSED_TIMEOUT)
                    handleTimeoutComplete()
                } catch (_: CancellationException) {
                    state = State.IDLE
                }
            }
    }

    private fun handleActionUp() {
        when (state) {
            State.TIMEOUT_WAIT -> {
                waitJob?.cancel()
                _actionType.value = ActionType.CLICK
                state = State.IDLE
            }
            State.RUNNING_FORWARD -> {
                reverse()
                state = State.RUNNING_BACKWARDS
            }
            else -> {}
        }
    }

    private fun handleActionCancel() {
        when (state) {
            State.TIMEOUT_WAIT -> {
                waitJob?.cancel()
                state = State.IDLE
            }
            State.RUNNING_FORWARD -> {
                reverse()
                state = State.RUNNING_BACKWARDS
            }
            else -> {}
        }
    }

    private fun handleAnimationStart() {
        vibrate(longPressHint)
        state = State.RUNNING_FORWARD
    }

    /** This function is called both when an animator completes or gets cancelled */
    private fun handleAnimationComplete() {
        if (state == State.RUNNING_FORWARD) {
            vibrate(snapEffect)
            _actionType.value = ActionType.LONG_PRESS
            _effectProgress.value = null
        }
        if (state != State.TIMEOUT_WAIT) {
            // This will happen if the animator did not finish by being cancelled
            state = State.IDLE
        }
    }

    private fun handleAnimationCancel() {
        _effectProgress.value = 0f
        startPressedTimeoutWait()
        state = State.TIMEOUT_WAIT
    }

    private fun handleTimeoutComplete() {
        if (state == State.TIMEOUT_WAIT && !effectAnimator.isRunning) {
            effectAnimator.start()
        }
    }

    fun clearActionType() {
        _actionType.value = null
    }

    enum class State {
        IDLE, /* The effect is idle waiting for touch input */
        TIMEOUT_WAIT, /* The effect is waiting for a [PRESSED_TIMEOUT] period */
        RUNNING_FORWARD, /* The effect is running normally */
        RUNNING_BACKWARDS, /* The effect was interrupted and is now running backwards */
    }

    /* A type of action to perform on the view depending on the effect's state and logic */
    enum class ActionType {
        CLICK,
        LONG_PRESS,
    }

    companion object {
        /**
         * A timeout to let the tile resolve if it is being swiped/scrolled. Since QS tiles are
         * inside a scrollable container, they will be considered pressed only after a tap timeout.
         */
        val PRESSED_TIMEOUT = ViewConfiguration.getTapTimeout().toLong() + 20L
    }
}
