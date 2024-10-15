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

package com.android.systemui.haptics.slider

import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.SeekBar
import androidx.annotation.VisibleForTesting
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A plugin added to a manager of a [android.widget.SeekBar] that adds dynamic haptic feedback.
 *
 * A [SliderStateProducer] is used as the producer of slider events, a
 * [SliderHapticFeedbackProvider] is used as the listener of slider states to play haptic feedback
 * depending on the state, and a [SliderStateTracker] is used as the state machine handler that
 * tracks and manipulates the slider state.
 */
class SeekbarHapticPlugin
@JvmOverloads
constructor(
    vibratorHelper: VibratorHelper,
    systemClock: SystemClock,
    sliderHapticFeedbackConfig: SliderHapticFeedbackConfig = SliderHapticFeedbackConfig(),
    private val sliderTrackerConfig: SeekableSliderTrackerConfig = SeekableSliderTrackerConfig(),
) {

    private val velocityTracker = VelocityTracker.obtain()

    private val dragVelocityProvider = SliderDragVelocityProvider {
        velocityTracker.computeCurrentVelocity(
            UNITS_SECOND,
            sliderHapticFeedbackConfig.maxVelocityToScale,
        )
        if (velocityTracker.isAxisSupported(sliderHapticFeedbackConfig.velocityAxis)) {
            velocityTracker.getAxisVelocity(sliderHapticFeedbackConfig.velocityAxis)
        } else {
            0f
        }
    }

    private val sliderEventProducer = SliderStateProducer()

    private val sliderHapticFeedbackProvider =
        SliderHapticFeedbackProvider(
            vibratorHelper,
            dragVelocityProvider,
            sliderHapticFeedbackConfig,
            systemClock,
        )

    private var sliderTracker: SliderStateTracker? = null

    private var pluginScope: CoroutineScope? = null

    val isTracking: Boolean
        get() = sliderTracker?.isTracking == true

    val trackerState: SliderState?
        get() = sliderTracker?.currentState

    /**
     * A waiting [Job] for a timer that estimates the key-up event when a key-down event is
     * received.
     *
     * This is useful for the cases where the slider is being operated by an external key, but the
     * release of the key is not easily accessible (e.g., the volume keys)
     */
    private var keyUpJob: Job? = null

    @VisibleForTesting
    val isKeyUpTimerWaiting: Boolean
        get() = keyUpJob != null && keyUpJob?.isActive == true

    /**
     * Specify the scope for the plugin's operations and start the slider tracker in this scope.
     * This also involves the key-up timer job.
     */
    fun startInScope(scope: CoroutineScope) {
        if (sliderTracker != null) stop()
        sliderTracker =
            SliderStateTracker(
                sliderHapticFeedbackProvider,
                sliderEventProducer,
                scope,
                sliderTrackerConfig,
            )
        pluginScope = scope
        sliderTracker?.startTracking()
    }

    /**
     * Stop the plugin
     *
     * This stops the tracking of slider states, events and triggers of haptic feedback.
     */
    fun stop() = sliderTracker?.stopTracking()

    /** React to a touch event */
    fun onTouchEvent(event: MotionEvent?) {
        when (event?.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> velocityTracker.clear()
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> velocityTracker.addMovement(event)
        }
    }

    /** onStartTrackingTouch event from the slider's [android.widget.SeekBar] */
    fun onStartTrackingTouch(seekBar: SeekBar) {
        if (isTracking) {
            sliderEventProducer.onStartTracking(true)
        }
    }

    /** onProgressChanged event from the slider's [android.widget.SeekBar] */
    fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (isTracking) {
            if (sliderTracker?.currentState == SliderState.IDLE && !fromUser) {
                // This case translates to the slider starting to track program changes
                sliderEventProducer.resetWithProgress(normalizeProgress(seekBar, progress))
                sliderEventProducer.onStartTracking(false)
            } else {
                sliderEventProducer.onProgressChanged(
                    fromUser,
                    normalizeProgress(seekBar, progress),
                )
            }
        }
    }

    /**
     * Normalize the integer progress of a SeekBar to the range from 0F to 1F.
     *
     * @param[seekBar] The SeekBar that reports a progress.
     * @param[progress] The integer progress of the SeekBar within its min and max values.
     * @return The progress in the range from 0F to 1F.
     */
    private fun normalizeProgress(seekBar: SeekBar, progress: Int): Float {
        if (seekBar.max == seekBar.min) {
            return 1.0f
        }
        val range = seekBar.max - seekBar.min
        return (progress - seekBar.min) / range.toFloat()
    }

    /** onStopTrackingTouch event from the slider's [android.widget.SeekBar] */
    fun onStopTrackingTouch(seekBar: SeekBar) {
        if (isTracking) {
            sliderEventProducer.onStopTracking(true)
        }
    }

    /** Programmatic changes have stopped */
    private fun onStoppedTrackingProgram() {
        if (isTracking) {
            sliderEventProducer.onStopTracking(false)
        }
    }

    /**
     * An external key was pressed (e.g., a volume key).
     *
     * This event is used to estimate the key-up event based on a running a timer as a waiting
     * coroutine in the [pluginScope]. A key-up event in a slider corresponds to an onArrowUp event.
     * Therefore, [onStoppedTrackingProgram] must be called after the timeout.
     */
    fun onKeyDown() {
        if (!isTracking) return

        if (isKeyUpTimerWaiting) {
            // Cancel the ongoing wait
            keyUpJob?.cancel()
        }
        keyUpJob =
            pluginScope?.launch {
                delay(KEY_UP_TIMEOUT)
                onStoppedTrackingProgram()
            }
    }

    companion object {
        const val KEY_UP_TIMEOUT = 60L
        private const val UNITS_SECOND = 1000
    }
}
