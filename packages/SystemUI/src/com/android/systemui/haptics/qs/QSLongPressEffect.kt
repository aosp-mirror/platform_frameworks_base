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

import android.content.ComponentName
import android.os.VibrationEffect
import android.service.quicksettings.Tile
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.DelegateTransitionAnimatorController
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.QSLog
import com.android.systemui.plugins.FalsingManager
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
class QSLongPressEffect
@Inject
constructor(
    private val vibratorHelper: VibratorHelper?,
    private val keyguardStateController: KeyguardStateController,
    private val falsingManager: FalsingManager,
    @QSLog private val logBuffer: LogBuffer,
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
        private set

    /** Haptic effects */
    private val durations =
        vibratorHelper?.getPrimitiveDurations(
            VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            VibrationEffect.Composition.PRIMITIVE_SPIN,
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
        logEvent(qsTile?.tileSpec, state, "action down received")
        when (state) {
            State.IDLE,
            // ACTION_DOWN typically only happens in State.IDLE but including CLICKED and
            // LONG_CLICKED just to be safe`b
            State.CLICKED,
            State.LONG_CLICKED -> {
                setState(State.TIMEOUT_WAIT)
            }
            State.RUNNING_BACKWARDS_FROM_UP,
            State.RUNNING_BACKWARDS_FROM_CANCEL -> callback?.onCancelAnimator()
            else -> {}
        }
    }

    fun handleActionUp() {
        logEvent(qsTile?.tileSpec, state, "action up received")
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
        logEvent(qsTile?.tileSpec, state, "animation started")
        if (state == State.TIMEOUT_WAIT) {
            vibrate(longPressHint)
            setState(State.RUNNING_FORWARD)
        }
    }

    /** This function is called both when an animator completes or gets cancelled */
    fun handleAnimationComplete() {
        logEvent(qsTile?.tileSpec, state, "animation completed")
        when (state) {
            State.RUNNING_FORWARD -> {
                val wasFalseLongTap = falsingManager.isFalseLongTap(FalsingManager.LOW_PENALTY)
                if (wasFalseLongTap) {
                    callback?.onResetProperties()
                    setState(State.IDLE)
                    logEvent(qsTile?.tileSpec, state, "false long click. No action triggered")
                } else if (keyguardStateController.isUnlocked) {
                    vibrate(snapEffect)
                    setState(State.LONG_CLICKED)
                    qsTile?.longClick(expandable)
                    logEvent(qsTile?.tileSpec, state, "long click action triggered")
                } else {
                    vibrate(snapEffect)
                    callback?.onResetProperties()
                    setState(State.IDLE)
                    qsTile?.longClick(expandable)
                    logEvent(
                        qsTile?.tileSpec,
                        state,
                        "properties reset and long click action triggered",
                    )
                }
            }
            State.RUNNING_BACKWARDS_FROM_UP -> {
                callback?.onEffectFinishedReversing()
                setState(getStateForClick())
                logEvent(qsTile?.tileSpec, state, "click action triggered")
                qsTile?.click(expandable)
            }
            State.RUNNING_BACKWARDS_FROM_CANCEL -> {
                callback?.onEffectFinishedReversing()
                setState(State.IDLE)
            }
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
        val isStateClickable = state == State.TIMEOUT_WAIT || state == State.IDLE

        // Ignore View-generated clicks on invalid states or if the bouncer is showing
        if (keyguardStateController.isPrimaryBouncerShowing || !isStateClickable) return false

        setState(getStateForClick())
        logEvent(qsTile?.tileSpec, state, "click action triggered")
        qsTile?.click(expandable)
        return true
    }

    /**
     * Get the appropriate state for a click action.
     *
     * In some occasions, the click action will not result in a subsequent action that resets the
     * state upon completion (e.g., a launch transition animation). In these cases, the state needs
     * to be reset before the click is dispatched.
     */
    @VisibleForTesting
    fun getStateForClick(): State {
        val isTileUnavailable = qsTile?.state?.state == Tile.STATE_UNAVAILABLE
        val handlesLongClick = qsTile?.state?.handlesLongClick == true
        return if (isTileUnavailable || !handlesLongClick || keyguardStateController.isShowing) {
            // The click event will not perform an action that resets the state. Therefore, this is
            // the last opportunity to reset the state back to IDLE.
            State.IDLE
        } else {
            State.CLICKED
        }
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
                effectDuration,
            )
        setState(State.IDLE)
        return true
    }

    fun resetState() = setState(State.IDLE)

    fun createExpandableFromView(view: View) {
        expandable =
            object : Expandable {
                override fun activityTransitionController(
                    launchCujType: Int?,
                    cookie: ActivityTransitionAnimator.TransitionCookie?,
                    component: ComponentName?,
                    returnCujType: Int?,
                ): ActivityTransitionAnimator.Controller? {
                    val delegatedController =
                        ActivityTransitionAnimator.Controller.fromView(
                            view,
                            launchCujType,
                            cookie,
                            component,
                            returnCujType,
                        )
                    return delegatedController?.let { createTransitionControllerDelegate(it) }
                }

                override fun dialogTransitionController(
                    cuj: DialogCuj?
                ): DialogTransitionAnimator.Controller? =
                    DialogTransitionAnimator.Controller.fromView(view, cuj)
            }
    }

    @VisibleForTesting
    fun createTransitionControllerDelegate(
        controller: ActivityTransitionAnimator.Controller
    ): DelegateTransitionAnimatorController {
        val delegated =
            object : DelegateTransitionAnimatorController(controller) {
                override fun onTransitionAnimationCancelled(newKeyguardOccludedState: Boolean?) {
                    if (state == State.LONG_CLICKED) {
                        setState(State.RUNNING_BACKWARDS_FROM_CANCEL)
                        callback?.onReverseAnimator(false)
                    }
                    delegate.onTransitionAnimationCancelled(newKeyguardOccludedState)
                }
            }
        return delegated
    }

    private fun logEvent(tileSpec: String?, state: State, event: String) {
        if (!DEBUG) return
        logBuffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = tileSpec
                str2 = event
                str3 = state.name
            },
            { "[long-press effect on $str1 tile] $str2 on state: $str3" },
        )
    }

    enum class State {
        IDLE, /* The effect is idle waiting for touch input */
        TIMEOUT_WAIT, /* The effect is waiting for a tap timeout period */
        RUNNING_FORWARD, /* The effect is running normally */
        /* The effect was interrupted by an ACTION_UP and is now running backwards */
        RUNNING_BACKWARDS_FROM_UP,
        /* The effect was cancelled by an ACTION_CANCEL or a shade collapse and is now running
        backwards */
        RUNNING_BACKWARDS_FROM_CANCEL,
        CLICKED, /* The effect has ended with a click */
        LONG_CLICKED, /* The effect has ended with a long-click */
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
        fun onReverseAnimator(playHaptics: Boolean = true)

        /** Cancel the effect animator */
        fun onCancelAnimator()
    }

    companion object {
        private const val TAG = "QSLongPressEffect"
        private const val DEBUG = true
    }
}
