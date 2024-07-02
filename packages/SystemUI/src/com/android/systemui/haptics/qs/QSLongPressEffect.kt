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
import androidx.annotation.VisibleForTesting
import com.android.systemui.animation.Expandable
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/**
 * A class that handles the long press visuo-haptic effect for a QS tile.
 *
 * The class can contain references to a [QSTile] and an [Expandable] to perform clicks and
 * long-clicks on the tile. The class also provides a [State] tha can be used to determine the
 * current state of the long press effect.
 *
 * @property[vibratorHelper] The [VibratorHelper] to deliver haptic effects.
 * @property[effectDuration] The duration of the effect in ms.
 */
// TODO(b/332902869): In addition from being injectable, we can consider making it a singleton
class QSLongPressEffect
@Inject
constructor(
    private val vibratorHelper: VibratorHelper?,
    private val keyguardStateController: KeyguardStateController,
) {

    var effectDuration = 0
        private set

    /** Current state */
    var state = State.IDLE
        private set

    /** Callback object for effect actions */
    var callback: Callback? = null

    /** The [QSTile] and [Expandable] used to perform a long-click and click actions */
    var qsTile: QSTile? = null
    var expandable: Expandable? = null

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
            }
            State.RUNNING_BACKWARDS_FROM_UP,
            State.RUNNING_BACKWARDS_FROM_CANCEL -> callback?.onCancelAnimator()
            else -> {}
        }
    }

    fun handleActionUp() {
        if (state == State.RUNNING_FORWARD) {
            setState(State.RUNNING_BACKWARDS_FROM_UP)
            callback?.onReverseAnimator()
        }
    }

    fun handleActionCancel() {
        when (state) {
            State.TIMEOUT_WAIT -> setState(State.IDLE)
            State.RUNNING_FORWARD -> {
                setState(State.RUNNING_BACKWARDS_FROM_CANCEL)
                callback?.onReverseAnimator()
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
                if (keyguardStateController.isUnlocked) {
                    qsTile?.longClick(expandable)
                } else {
                    callback?.onResetProperties()
                    qsTile?.longClick(expandable)
                }
            }
            State.RUNNING_BACKWARDS_FROM_UP -> {
                setState(State.IDLE)
                callback?.onEffectFinishedReversing()
                qsTile?.click(expandable)
            }
            State.RUNNING_BACKWARDS_FROM_CANCEL -> setState(State.IDLE)
            else -> {}
        }
    }

    fun handleAnimationCancel() {
        setState(State.TIMEOUT_WAIT)
    }

    fun handleTimeoutComplete() {
        if (state == State.TIMEOUT_WAIT) {
            callback?.onStartAnimator()
        }
    }

    fun onTileClick(): Boolean {
        if (state == State.TIMEOUT_WAIT || state == State.IDLE) {
            setState(State.IDLE)
            qsTile?.let {
                it.click(expandable)
                return true
            }
        }
        return false
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
        return true
    }

    enum class State {
        IDLE, /* The effect is idle waiting for touch input */
        TIMEOUT_WAIT, /* The effect is waiting for a tap timeout period */
        RUNNING_FORWARD, /* The effect is running normally */
        /* The effect was interrupted by an ACTION_UP and is now running backwards */
        RUNNING_BACKWARDS_FROM_UP,
        /* The effect was interrupted by an ACTION_CANCEL and is now running backwards */
        RUNNING_BACKWARDS_FROM_CANCEL,
    }

    /** Callbacks to notify view and animator actions */
    interface Callback {

        /** Reset the tile visual properties */
        fun onResetProperties()

        /** Event where the effect completed by being reversed */
        fun onEffectFinishedReversing()

        /** Start the effect animator */
        fun onStartAnimator()

        /** Reverse the effect animator */
        fun onReverseAnimator()

        /** Cancel the effect animator */
        fun onCancelAnimator()
    }
}
