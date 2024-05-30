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

import android.os.VibrationEffect
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.animation.Expandable
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.statusbar.VibratorHelper
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

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
    keyguardInteractor: KeyguardInteractor,
) {

    var effectDuration = 0
        private set

    /** Current state */
    var state = State.IDLE
        private set

    /** The QSTile and Expandable used to perform a long-click and click actions */
    var qsTile: QSTile? = null
    var expandable: Expandable? = null

    /** Flow for view control and action */
    private val _postedActionType = MutableStateFlow<ActionType?>(null)
    val actionType: Flow<ActionType?> =
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

    /** Haptic effects */
    private val durations =
        vibratorHelper?.getPrimitiveDurations(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_SPIN
        )

    private var longPressHint: VibrationEffect? = null

    private val snapEffect = LongPressHapticBuilder.createSnapEffect()

    val hasInitialized: Boolean
        get() = longPressHint != null

    @VisibleForTesting
    fun setState(newState: State) {
        state = newState
    }

    fun playReverseHaptics(pausedProgress: Float) {
        val effect =
            LongPressHapticBuilder.createReversedEffect(
                pausedProgress,
                durations?.get(0) ?: 0,
                effectDuration,
            )
        vibratorHelper?.cancel()
        vibrate(effect)
    }

    private fun vibrate(effect: VibrationEffect?) {
        if (vibratorHelper != null && effect != null) {
            vibratorHelper.vibrate(effect)
        }
    }

    fun handleActionDown() {
        when (state) {
            State.IDLE -> {
                setState(State.TIMEOUT_WAIT)
                _postedActionType.value = ActionType.WAIT_TAP_TIMEOUT
            }
            State.RUNNING_BACKWARDS -> _postedActionType.value = ActionType.CANCEL_ANIMATOR
            else -> {}
        }
    }

    fun handleActionUp() {
        if (state == State.RUNNING_FORWARD) {
            _postedActionType.value = ActionType.REVERSE_ANIMATOR
            setState(State.RUNNING_BACKWARDS)
        }
    }

    fun handleActionCancel() {
        when (state) {
            State.TIMEOUT_WAIT -> {
                setState(State.IDLE)
                clearActionType()
            }
            State.RUNNING_FORWARD -> {
                _postedActionType.value = ActionType.REVERSE_ANIMATOR
                setState(State.RUNNING_BACKWARDS)
            }
            else -> {}
        }
    }

    fun handleAnimationStart() {
        vibrate(longPressHint)
        setState(State.RUNNING_FORWARD)
    }

    /** This function is called both when an animator completes or gets cancelled */
    fun handleAnimationComplete() {
        when (state) {
            State.RUNNING_FORWARD -> {
                setState(State.IDLE)
                vibrate(snapEffect)
                _postedActionType.value = ActionType.LONG_PRESS
            }
            State.RUNNING_BACKWARDS -> {
                setState(State.IDLE)
                clearActionType()
            }
            else -> {}
        }
    }

    fun handleAnimationCancel() {
        setState(State.TIMEOUT_WAIT)
        _postedActionType.value = ActionType.WAIT_TAP_TIMEOUT
    }

    fun handleTimeoutComplete() {
        if (state == State.TIMEOUT_WAIT) {
            _postedActionType.value = ActionType.START_ANIMATOR
        }
    }

    fun clearActionType() {
        _postedActionType.value = null
    }

    /**
     * Reset the effect with a new effect duration.
     *
     * @param[duration] New duration for the long-press effect
     * @return true if the effect initialized correctly
     */
    fun initializeEffect(duration: Int): Boolean {
        // The effect can't initialize with a negative duration
        if (duration <= 0) return false

        // There is no need to re-initialize if the duration has not changed
        if (duration == effectDuration) return true

        effectDuration = duration
        longPressHint =
            LongPressHapticBuilder.createLongPressHint(
                durations?.get(0) ?: LongPressHapticBuilder.INVALID_DURATION,
                durations?.get(1) ?: LongPressHapticBuilder.INVALID_DURATION,
                effectDuration
            )
        setState(State.IDLE)
        clearActionType()
        return true
    }

    fun onTileClick(): Boolean {
        if (state == State.TIMEOUT_WAIT) {
            setState(State.IDLE)
            clearActionType()
            qsTile?.let {
                it.click(expandable)
                return true
            }
        }
        return false
    }

    enum class State {
        IDLE, /* The effect is idle waiting for touch input */
        TIMEOUT_WAIT, /* The effect is waiting for a [PRESSED_TIMEOUT] period */
        RUNNING_FORWARD, /* The effect is running normally */
        RUNNING_BACKWARDS, /* The effect was interrupted and is now running backwards */
    }

    /* A type of action to perform on the view depending on the effect's state and logic */
    enum class ActionType {
        WAIT_TAP_TIMEOUT,
        LONG_PRESS,
        RESET_AND_LONG_PRESS,
        START_ANIMATOR,
        REVERSE_ANIMATOR,
        CANCEL_ANIMATOR,
    }
}
