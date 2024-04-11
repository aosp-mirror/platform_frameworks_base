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
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * A class that handles the long press visuo-haptic effect for a QS tile.
 *
 * The class is also a [View.OnTouchListener] to handle the touch events, clicks and long-press
 * gestures of the tile. The class also provides a [State] tha can be used to determine the current
 * state of the long press effect.
 *
 * @property[vibratorHelper] The [VibratorHelper] to deliver haptic effects.
 * @property[effectDuration] The duration of the effect in ms.
 */
// TODO(b/332902869): In addition from being injectable, we can consider making it a singleton
class QSLongPressEffect
@Inject
constructor(
    private val vibratorHelper: VibratorHelper?,
    val keyguardInteractor: KeyguardInteractor,
    @Background bgScope: CoroutineScope,
) : View.OnTouchListener {

    private var effectDuration = 0

    /** Current state */
    private var _state = MutableStateFlow(State.IDLE)
    val state = _state.stateIn(bgScope, SharingStarted.Lazily, State.IDLE)

    /** Flows for view control and action */
    private val _effectProgress = MutableStateFlow<Float?>(null)
    val effectProgress = _effectProgress.stateIn(bgScope, SharingStarted.Lazily, null)

    // Actions to perform
    private val _postedActionType = MutableStateFlow<ActionType?>(null)
    val actionType: StateFlow<ActionType?> =
        combine(
                _postedActionType,
                keyguardInteractor.isKeyguardDismissible,
            ) { action, isDismissible ->
                if (!isDismissible && action == ActionType.LONG_PRESS) {
                    ActionType.RESET_AND_LONG_PRESS
                } else {
                    action
                }
            }
            .stateIn(bgScope, SharingStarted.Lazily, null)

    // Should a tap timeout countdown begin
    val shouldWaitForTapTimeout: Flow<Boolean> = state.map { it == State.TIMEOUT_WAIT }

    /** Haptic effects */
    private val durations =
        vibratorHelper?.getPrimitiveDurations(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_SPIN
        )

    private var longPressHint: VibrationEffect? = null

    private val snapEffect = LongPressHapticBuilder.createSnapEffect()

    private var effectAnimator: ValueAnimator? = null

    val hasInitialized: Boolean
        get() = longPressHint != null && effectAnimator != null

    @VisibleForTesting
    fun setState(state: State) {
        _state.value = state
    }

    private fun reverse() {
        effectAnimator?.let {
            val pausedProgress = it.animatedFraction
            val effect =
                LongPressHapticBuilder.createReversedEffect(
                    pausedProgress,
                    durations?.get(0) ?: 0,
                    effectDuration,
                )
            vibratorHelper?.cancel()
            vibrate(effect)
            it.reverse()
        }
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
        when (_state.value) {
            State.IDLE -> {
                setState(State.TIMEOUT_WAIT)
            }
            State.RUNNING_BACKWARDS -> effectAnimator?.cancel()
            else -> {}
        }
    }

    private fun handleActionUp() {
        when (_state.value) {
            State.TIMEOUT_WAIT -> {
                _postedActionType.value = ActionType.CLICK
                setState(State.IDLE)
            }
            State.RUNNING_FORWARD -> {
                reverse()
                setState(State.RUNNING_BACKWARDS)
            }
            else -> {}
        }
    }

    private fun handleActionCancel() {
        when (_state.value) {
            State.TIMEOUT_WAIT -> {
                setState(State.IDLE)
            }
            State.RUNNING_FORWARD -> {
                reverse()
                setState(State.RUNNING_BACKWARDS)
            }
            else -> {}
        }
    }

    private fun handleAnimationStart() {
        vibrate(longPressHint)
        setState(State.RUNNING_FORWARD)
    }

    /** This function is called both when an animator completes or gets cancelled */
    private fun handleAnimationComplete() {
        if (_state.value == State.RUNNING_FORWARD) {
            vibrate(snapEffect)
            _postedActionType.value = ActionType.LONG_PRESS
            _effectProgress.value = null
        }
        if (_state.value != State.TIMEOUT_WAIT) {
            // This will happen if the animator did not finish by being cancelled
            setState(State.IDLE)
        }
    }

    private fun handleAnimationCancel() {
        _effectProgress.value = null
        setState(State.TIMEOUT_WAIT)
    }

    fun handleTimeoutComplete() {
        if (_state.value == State.TIMEOUT_WAIT && effectAnimator?.isRunning == false) {
            effectAnimator?.start()
        }
    }

    fun clearActionType() {
        _postedActionType.value = null
    }

    /** Reset the effect by going back to a default [IDLE] state */
    fun resetEffect() {
        if (effectAnimator?.isRunning == true) {
            effectAnimator?.cancel()
        }
        longPressHint = null
        effectAnimator = null
        _effectProgress.value = null
        _postedActionType.value = null
        setState(State.IDLE)
    }

    /**
     * Reset the effect with a new effect duration.
     *
     * @param[duration] New duration for the long-press effect
     * @return true if the effect initialized correctly
     */
    fun initializeEffect(duration: Int): Boolean {
        // The effect can't reset if it is running
        if (duration <= 0) return false

        resetEffect()
        effectDuration = duration
        effectAnimator =
            ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = effectDuration.toLong()
                interpolator = AccelerateDecelerateInterpolator()

                doOnStart { handleAnimationStart() }
                addUpdateListener { _effectProgress.value = animatedValue as Float }
                doOnEnd { handleAnimationComplete() }
                doOnCancel { handleAnimationCancel() }
            }
        longPressHint =
            LongPressHapticBuilder.createLongPressHint(
                durations?.get(0) ?: LongPressHapticBuilder.INVALID_DURATION,
                durations?.get(1) ?: LongPressHapticBuilder.INVALID_DURATION,
                effectDuration
            )
        return true
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
        RESET_AND_LONG_PRESS,
    }

    companion object {
        /**
         * A timeout to let the tile resolve if it is being swiped/scrolled. Since QS tiles are
         * inside a scrollable container, they will be considered pressed only after a tap timeout.
         */
        val PRESSED_TIMEOUT = ViewConfiguration.getTapTimeout().toLong() + 20L
    }
}
