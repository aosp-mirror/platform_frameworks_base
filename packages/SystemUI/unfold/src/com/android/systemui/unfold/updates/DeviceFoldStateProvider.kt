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

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.FloatRange
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.core.util.Consumer
import com.android.systemui.unfold.compat.INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP
import com.android.systemui.unfold.config.UnfoldTransitionConfig
import com.android.systemui.unfold.updates.hinge.FULLY_CLOSED_DEGREES
import com.android.systemui.unfold.updates.hinge.FULLY_OPEN_DEGREES
import com.android.systemui.unfold.updates.hinge.HingeAngleProvider
import com.android.systemui.unfold.updates.screen.ScreenStatusProvider
import com.android.systemui.unfold.util.CurrentActivityTypeProvider
import com.android.systemui.unfold.util.UnfoldKeyguardVisibilityProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class DeviceFoldStateProvider
@AssistedInject
constructor(
    config: UnfoldTransitionConfig,
    private val context: Context,
    private val screenStatusProvider: ScreenStatusProvider,
    private val activityTypeProvider: CurrentActivityTypeProvider,
    private val unfoldKeyguardVisibilityProvider: UnfoldKeyguardVisibilityProvider,
    private val foldProvider: FoldProvider,
    @Assisted private val hingeAngleProvider: HingeAngleProvider,
    @Assisted private val rotationChangeProvider: RotationChangeProvider,
    @Assisted private val progressHandler: Handler,
) : FoldStateProvider {
    private val outputListeners = CopyOnWriteArrayList<FoldStateProvider.FoldUpdatesListener>()

    @FoldStateProvider.FoldUpdate private var lastFoldUpdate: Int? = null

    @FloatRange(from = 0.0, to = 180.0) private var lastHingeAngle: Float = 0f
    @FloatRange(from = 0.0, to = 180.0) private var lastHingeAngleBeforeTransition: Float = 0f

    private val hingeAngleListener = HingeAngleListener()
    private val screenListener = ScreenStatusListener()
    private val foldStateListener = FoldStateListener()
    private val timeoutRunnable = Runnable { cancelAnimation() }
    private val rotationListener = FoldRotationListener()
    private val progressExecutor = Executor { progressHandler.post(it) }

    /**
     * Time after which [FOLD_UPDATE_FINISH_HALF_OPEN] is emitted following a
     * [FOLD_UPDATE_START_CLOSING] or [FOLD_UPDATE_START_OPENING] event, if an end state is not
     * reached.
     */
    private val halfOpenedTimeoutMillis: Int = config.halfFoldedTimeoutMillis

    private var isFolded = false
    private var isScreenOn = false
    private var isUnfoldHandled = true
    private var isStarted = false

    override fun start() {
        if (isStarted) return
        foldProvider.registerCallback(foldStateListener, progressExecutor)
        // TODO(b/277879146): get callbacks in the background
        screenStatusProvider.addCallback(screenListener)
        hingeAngleProvider.addCallback(hingeAngleListener)
        rotationChangeProvider.addCallback(rotationListener)
        activityTypeProvider.init()
        isStarted = true
    }

    override fun stop() {
        screenStatusProvider.removeCallback(screenListener)
        foldProvider.unregisterCallback(foldStateListener)
        hingeAngleProvider.removeCallback(hingeAngleListener)
        hingeAngleProvider.stop()
        rotationChangeProvider.removeCallback(rotationListener)
        activityTypeProvider.uninit()
        isStarted = false
    }

    override fun addCallback(listener: FoldStateProvider.FoldUpdatesListener) {
        outputListeners.add(listener)
    }

    override fun removeCallback(listener: FoldStateProvider.FoldUpdatesListener) {
        outputListeners.remove(listener)
    }

    override val isFinishedOpening: Boolean
        get() =
            !isFolded &&
                (lastFoldUpdate == FOLD_UPDATE_FINISH_FULL_OPEN ||
                    lastFoldUpdate == FOLD_UPDATE_FINISH_HALF_OPEN)

    private val isTransitionInProgress: Boolean
        get() =
            lastFoldUpdate == FOLD_UPDATE_START_OPENING ||
                lastFoldUpdate == FOLD_UPDATE_START_CLOSING

    private fun onHingeAngle(angle: Float) {
        assertInProgressThread()
        if (DEBUG) {
            Log.d(
                TAG,
                "Hinge angle: $angle, " +
                    "lastHingeAngle: $lastHingeAngle, " +
                    "lastHingeAngleBeforeTransition: $lastHingeAngleBeforeTransition"
            )
        }

        val currentDirection =
            if (angle < lastHingeAngle) FOLD_UPDATE_START_CLOSING else FOLD_UPDATE_START_OPENING
        val changedDirectionWhileInTransition =
            isTransitionInProgress && currentDirection != lastFoldUpdate
        val unfoldedPastThresholdSinceLastTransition =
            angle - lastHingeAngleBeforeTransition > HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES
        if (changedDirectionWhileInTransition || unfoldedPastThresholdSinceLastTransition) {
            lastHingeAngleBeforeTransition = lastHingeAngle
        }

        val isClosing = angle < lastHingeAngleBeforeTransition
        val transitionUpdate =
            if (isClosing) FOLD_UPDATE_START_CLOSING else FOLD_UPDATE_START_OPENING
        val angleChangeSurpassedThreshold =
            Math.abs(angle - lastHingeAngleBeforeTransition) > HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES
        val isFullyOpened = FULLY_OPEN_DEGREES - angle < FULLY_OPEN_THRESHOLD_DEGREES
        val eventNotAlreadyDispatched = lastFoldUpdate != transitionUpdate
        val screenAvailableEventSent = isUnfoldHandled
        val isOnLargeScreen = isOnLargeScreen()

        if (
            angleChangeSurpassedThreshold && // Do not react immediately to small changes in angle
                eventNotAlreadyDispatched && // we haven't sent transition event already
                !isFullyOpened && // do not send transition event if we are in fully opened hinge
                // angle range as closing threshold could overlap this range
                screenAvailableEventSent && // do not send transition event if we are still in the
                // process of turning on the inner display
                isClosingThresholdMet(angle) && // hinge angle is below certain threshold.
                isOnLargeScreen // Avoids sending closing event when on small screen.
        // Start event is sent regardless due to hall sensor.
        ) {
            notifyFoldUpdate(transitionUpdate, angle)
        }

        if (isTransitionInProgress) {
            if (isFullyOpened) {
                notifyFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN, angle)
                cancelTimeout()
            } else {
                // The timeout will trigger some constant time after the last angle update.
                rescheduleAbortAnimationTimeout()
            }
        }

        lastHingeAngle = angle
        outputListeners.forEach { it.onHingeAngleUpdate(angle) }
    }

    private fun isClosingThresholdMet(currentAngle: Float): Boolean {
        val closingThreshold = getClosingThreshold()
        return closingThreshold == null || currentAngle < closingThreshold
    }

    /**
     * Fold animation should be started only after the threshold returned here.
     *
     * This has been introduced because the fold animation might be distracting/unwanted on top of
     * apps that support table-top/HALF_FOLDED mode. Only for launcher, there is no threshold.
     */
    private fun getClosingThreshold(): Int? {
        val isHomeActivity = activityTypeProvider.isHomeActivity ?: return null
        val isKeyguardVisible = unfoldKeyguardVisibilityProvider.isKeyguardVisible == true

        if (DEBUG) {
            Log.d(TAG, "isHomeActivity=$isHomeActivity, isOnKeyguard=$isKeyguardVisible")
        }

        return if (isHomeActivity || isKeyguardVisible) {
            null
        } else {
            START_CLOSING_ON_APPS_THRESHOLD_DEGREES
        }
    }

    private inner class FoldStateListener : FoldProvider.FoldCallback {
        override fun onFoldUpdated(isFolded: Boolean) {
            assertInProgressThread()
            this@DeviceFoldStateProvider.isFolded = isFolded
            lastHingeAngle = FULLY_CLOSED_DEGREES

            if (isFolded) {
                hingeAngleProvider.stop()
                notifyFoldUpdate(FOLD_UPDATE_FINISH_CLOSED, lastHingeAngle)
                cancelTimeout()
                isUnfoldHandled = false
            } else {
                notifyFoldUpdate(FOLD_UPDATE_START_OPENING, lastHingeAngle)
                rescheduleAbortAnimationTimeout()
                hingeAngleProvider.start()
            }
        }
    }

    private inner class FoldRotationListener : RotationChangeProvider.RotationListener {
        @WorkerThread
        override fun onRotationChanged(newRotation: Int) {
            assertInProgressThread()
            if (isTransitionInProgress) cancelAnimation()
        }
    }

    private fun notifyFoldUpdate(@FoldStateProvider.FoldUpdate update: Int, angle: Float) {
        if (DEBUG) {
            Log.d(TAG, update.name())
        }
        val previouslyTransitioning = isTransitionInProgress

        outputListeners.forEach { it.onFoldUpdate(update) }
        lastFoldUpdate = update

        if (previouslyTransitioning != isTransitionInProgress) {
            lastHingeAngleBeforeTransition = angle
        }
    }

    private fun rescheduleAbortAnimationTimeout() {
        if (isTransitionInProgress) {
            cancelTimeout()
        }
        progressHandler.postDelayed(timeoutRunnable, halfOpenedTimeoutMillis.toLong())
    }

    private fun cancelTimeout() {
        progressHandler.removeCallbacks(timeoutRunnable)
    }

    private fun cancelAnimation(): Unit =
        notifyFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN, lastHingeAngle)

    private inner class ScreenStatusListener : ScreenStatusProvider.ScreenListener {

        override fun onScreenTurnedOn() {
            executeInProgressThread {
                // Trigger this event only if we are unfolded and this is the first screen
                // turned on event since unfold started. This prevents running the animation when
                // turning on the internal display using the power button.
                // Initially isUnfoldHandled is true so it will be reset to false *only* when we
                // receive 'folded' event. If SystemUI started when device is already folded it will
                // still receive 'folded' event on startup.
                if (!isFolded && !isUnfoldHandled) {
                    outputListeners.forEach { it.onUnfoldedScreenAvailable() }
                    isUnfoldHandled = true
                }
            }
        }

        override fun markScreenAsTurnedOn() {
            executeInProgressThread {
                if (!isFolded) {
                    isUnfoldHandled = true
                }
            }
        }

        override fun onScreenTurningOn() {
            executeInProgressThread {
                isScreenOn = true
                updateHingeAngleProviderState()
            }
        }

        override fun onScreenTurningOff() {
            executeInProgressThread {
                isScreenOn = false
                updateHingeAngleProviderState()
            }
        }

        /**
         * Needed just for compatibility while not all data sources are providing data in the
         * background.
         *
         * TODO(b/277879146): Remove once ScreeStatusProvider provides in the background.
         */
        private fun executeInProgressThread(f: () -> Unit) {
            progressHandler.post { f() }
        }
    }

    private fun isOnLargeScreen(): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >
            INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP
    }

    /** While the screen is off or the device is folded, hinge angle updates are not needed. */
    private fun updateHingeAngleProviderState() {
        assertInProgressThread()
        if (isScreenOn && !isFolded) {
            hingeAngleProvider.start()
        } else {
            hingeAngleProvider.stop()
        }
    }

    private inner class HingeAngleListener : Consumer<Float> {
        override fun accept(angle: Float) {
            assertInProgressThread()
            onHingeAngle(angle)
        }
    }

    private fun assertInProgressThread() {
        check(progressHandler.looper.isCurrentThread) {
            val progressThread = progressHandler.looper.thread
            val thisThread = Thread.currentThread()
            """should be called from the progress thread.
                progressThread=$progressThread tid=${progressThread.id}
                Thread.currentThread()=$thisThread tid=${thisThread.id}"""
                .trimMargin()
        }
    }

    @AssistedFactory
    interface Factory {
        /** Creates a [DeviceFoldStateProvider] using the provided dependencies. */
        fun create(
            hingeAngleProvider: HingeAngleProvider,
            rotationChangeProvider: RotationChangeProvider,
            progressHandler: Handler,
        ): DeviceFoldStateProvider
    }
}

fun @receiver:FoldStateProvider.FoldUpdate Int.name() =
    when (this) {
        FOLD_UPDATE_START_OPENING -> "START_OPENING"
        FOLD_UPDATE_START_CLOSING -> "START_CLOSING"
        FOLD_UPDATE_FINISH_HALF_OPEN -> "FINISH_HALF_OPEN"
        FOLD_UPDATE_FINISH_FULL_OPEN -> "FINISH_FULL_OPEN"
        FOLD_UPDATE_FINISH_CLOSED -> "FINISH_CLOSED"
        else -> "UNKNOWN"
    }

private const val TAG = "DeviceFoldProvider"
private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

/** Threshold after which we consider the device fully unfolded. */
@VisibleForTesting const val FULLY_OPEN_THRESHOLD_DEGREES = 15f

/** Threshold after which hinge angle updates are considered. This is to eliminate noise. */
@VisibleForTesting const val HINGE_ANGLE_CHANGE_THRESHOLD_DEGREES = 7.5f

/** Fold animation on top of apps only when the angle exceeds this threshold. */
@VisibleForTesting const val START_CLOSING_ON_APPS_THRESHOLD_DEGREES = 60
