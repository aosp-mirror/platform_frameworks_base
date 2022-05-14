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
package com.android.systemui.unfold.updates

import android.os.Handler
import android.util.Log
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import androidx.core.util.Consumer
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.dagger.UnfoldMain
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdate
import com.android.systemui.unfold.updates.FoldStateProvider.FoldUpdatesListener
import com.android.systemui.unfold.updates.hinge.FULLY_CLOSED_DEGREES
import com.android.systemui.unfold.updates.hinge.FULLY_OPEN_DEGREES
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.util.CurrentActivityTypeProvider
import java.util.concurrent.Executor
import javax.inject.Inject

class DeviceFoldStateProvider
@Inject
constructor(
    config: UnfoldTransitionConfig,
    private val hingeAngleProvider: HingeAngleProvider,
    private val screenStatusProvider: ScreenStatusProvider,
    private val foldProvider: FoldProvider,
    private val activityTypeProvider: CurrentActivityTypeProvider,
    @UnfoldMain private val mainExecutor: Executor,
    @UnfoldMain private val handler: Handler
) : FoldStateProvider {

    private val outputListeners: MutableList<FoldUpdatesListener> = mutableListOf()

    @FoldUpdate
    private var lastFoldUpdate: Int? = null

    @FloatRange(from = 0.0, to = 180.0)
    private var lastHingeAngle: Float = 0f

    private val hingeAngleListener = HingeAngleListener()
    private val screenListener = ScreenStatusListener()
    private val foldStateListener = FoldStateListener()
    private val timeoutRunnable = TimeoutRunnable()

    /**
     * Time after which [FOLD_UPDATE_FINISH_HALF_OPEN] is emitted following a
     * [FOLD_UPDATE_START_CLOSING] or [FOLD_UPDATE_START_OPENING] event, if an end state is not
     * reached.
     */
    private val halfOpenedTimeoutMillis: Int = config.halfFoldedTimeoutMillis

    private var isFolded = false
    private var isUnfoldHandled = true

    override fun start() {
        foldProvider.registerCallback(foldStateListener, mainExecutor)
        screenStatusProvider.addCallback(screenListener)
        hingeAngleProvider.addCallback(hingeAngleListener)
    }

    override fun stop() {
        screenStatusProvider.removeCallback(screenListener)
        foldProvider.unregisterCallback(foldStateListener)
        hingeAngleProvider.removeCallback(hingeAngleListener)
        hingeAngleProvider.stop()
    }

    override fun addCallback(listener: FoldUpdatesListener) {
        outputListeners.add(listener)
    }

    override fun removeCallback(listener: FoldUpdatesListener) {
        outputListeners.remove(listener)
    }

    override val isFinishedOpening: Boolean
        get() = !isFolded &&
                (lastFoldUpdate == FOLD_UPDATE_FINISH_FULL_OPEN ||
                        lastFoldUpdate == FOLD_UPDATE_FINISH_HALF_OPEN)

    private val isTransitionInProgress: Boolean
        get() =
            lastFoldUpdate == FOLD_UPDATE_START_OPENING ||
                    lastFoldUpdate == FOLD_UPDATE_START_CLOSING

    private fun onHingeAngle(angle: Float) {
        if (DEBUG) {
            Log.d(TAG, "Hinge angle: $angle, lastHingeAngle: $lastHingeAngle")
        }

        val isClosing = angle < lastHingeAngle
        val closingThreshold = getClosingThreshold()
        val closingThresholdMet = closingThreshold == null || angle < closingThreshold
        val isFullyOpened = FULLY_OPEN_DEGREES - angle < FULLY_OPEN_THRESHOLD_DEGREES
        val closingEventDispatched = lastFoldUpdate == FOLD_UPDATE_START_CLOSING

        if (isClosing && closingThresholdMet && !closingEventDispatched && !isFullyOpened) {
            notifyFoldUpdate(FOLD_UPDATE_START_CLOSING)
        }

        if (isTransitionInProgress) {
            if (isFullyOpened) {
                notifyFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)
                cancelTimeout()
            } else {
                // The timeout will trigger some constant time after the last angle update.
                rescheduleAbortAnimationTimeout()
            }
        }

        lastHingeAngle = angle
        outputListeners.forEach { it.onHingeAngleUpdate(angle) }
    }

    /**
     * Fold animation should be started only after the threshold returned here.
     *
     * This has been introduced because the fold animation might be distracting/unwanted on top of
     * apps that support table-top/HALF_FOLDED mode. Only for launcher, there is no threshold.
     */
    private fun getClosingThreshold(): Int? {
        val isHomeActivity = activityTypeProvider.isHomeActivity ?: return null

        if (DEBUG) {
            Log.d(TAG, "isHomeActivity=$isHomeActivity")
        }

        return if (isHomeActivity) {
            null
        } else {
            START_CLOSING_ON_APPS_THRESHOLD_DEGREES
        }
    }

    private inner class FoldStateListener : FoldProvider.FoldCallback {
        override fun onFoldUpdated(isFolded: Boolean) {
            this@DeviceFoldStateProvider.isFolded = isFolded
            lastHingeAngle = FULLY_CLOSED_DEGREES

            if (isFolded) {
                hingeAngleProvider.stop()
                notifyFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)
                cancelTimeout()
                isUnfoldHandled = false
            } else {
                notifyFoldUpdate(FOLD_UPDATE_START_OPENING)
                rescheduleAbortAnimationTimeout()
                hingeAngleProvider.start()
            }
        }
    }

    private fun notifyFoldUpdate(@FoldUpdate update: Int) {
        if (DEBUG) {
            Log.d(TAG, stateToString(update))
        }
        outputListeners.forEach { it.onFoldUpdate(update) }
        lastFoldUpdate = update
    }

    private fun rescheduleAbortAnimationTimeout() {
        if (isTransitionInProgress) {
            cancelTimeout()
        }
        handler.postDelayed(timeoutRunnable, halfOpenedTimeoutMillis.toLong())
    }

    private fun cancelTimeout() {
        handler.removeCallbacks(timeoutRunnable)
    }

    private inner class ScreenStatusListener : ScreenStatusProvider.ScreenListener {

        override fun onScreenTurnedOn() {
            // Trigger this event only if we are unfolded and this is the first screen
            // turned on event since unfold started. This prevents running the animation when
            // turning on the internal display using the power button.
            // Initially isUnfoldHandled is true so it will be reset to false *only* when we
            // receive 'folded' event. If SystemUI started when device is already folded it will
            // still receive 'folded' event on startup.
            if (!isFolded && !isUnfoldHandled) {
                outputListeners.forEach { it.onFoldUpdate(FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE) }
                isUnfoldHandled = true
            }
        }
    }

    private inner class HingeAngleListener : Consumer<Float> {
        override fun accept(angle: Float) {
            onHingeAngle(angle)
        }
    }

    private inner class TimeoutRunnable : Runnable {
        override fun run() {
            notifyFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)
        }
    }
}

private fun stateToString(@FoldUpdate update: Int): String {
    return when (update) {
        FOLD_UPDATE_START_OPENING -> "START_OPENING"
        FOLD_UPDATE_START_CLOSING -> "START_CLOSING"
        FOLD_UPDATE_UNFOLDED_SCREEN_AVAILABLE -> "UNFOLDED_SCREEN_AVAILABLE"
        FOLD_UPDATE_FINISH_HALF_OPEN -> "FINISH_HALF_OPEN"
        FOLD_UPDATE_FINISH_FULL_OPEN -> "FINISH_FULL_OPEN"
        FOLD_UPDATE_FINISH_CLOSED -> "FINISH_CLOSED"
        else -> "UNKNOWN"
    }
}

private const val TAG = "DeviceFoldProvider"
private const val DEBUG = false

/** Threshold after which we consider the device fully unfolded. */
@VisibleForTesting
const val FULLY_OPEN_THRESHOLD_DEGREES = 15f

/** Fold animation on top of apps only when the angle exceeds this threshold. */
@VisibleForTesting
const val START_CLOSING_ON_APPS_THRESHOLD_DEGREES = 60
