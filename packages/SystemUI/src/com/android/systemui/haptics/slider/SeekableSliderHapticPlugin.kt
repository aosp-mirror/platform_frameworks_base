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
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.VibratorHelper
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A plugin added to a manager of a [android.widget.SeekBar] that adds dynamic haptic feedback.
 *
 * A [SeekableSliderEventProducer] is used as the producer of slider events, a
 * [SliderHapticFeedbackProvider] is used as the listener of slider states to play haptic feedback
 * depending on the state, and a [SeekableSliderTracker] is used as the state machine handler that
 * tracks and manipulates the slider state.
 */
class SeekableSliderHapticPlugin
@JvmOverloads
constructor(
    vibratorHelper: VibratorHelper,
    systemClock: SystemClock,
    @Main private val mainDispatcher: CoroutineDispatcher,
    @Application private val applicationScope: CoroutineScope,
    sliderHapticFeedbackConfig: SliderHapticFeedbackConfig = SliderHapticFeedbackConfig(),
    sliderTrackerConfig: SeekableSliderTrackerConfig = SeekableSliderTrackerConfig(),
) {

    private val velocityTracker = VelocityTracker.obtain()

    private val sliderEventProducer = SeekableSliderEventProducer()

    private val sliderHapticFeedbackProvider =
        SliderHapticFeedbackProvider(
            vibratorHelper,
            velocityTracker,
            sliderHapticFeedbackConfig,
            systemClock,
        )

    private val sliderTracker =
        SeekableSliderTracker(
            sliderHapticFeedbackProvider,
            sliderEventProducer,
            mainDispatcher,
            sliderTrackerConfig,
        )

    val isTracking: Boolean
        get() = sliderTracker.isTracking

    val trackerState: SliderState
        get() = sliderTracker.currentState

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
     * Start the plugin.
     *
     * This starts the tracking of slider states, events and triggering of haptic feedback.
     */
    fun start() {
        if (!isTracking) {
            sliderTracker.startTracking()
        }
    }

    /**
     * Stop the plugin
     *
     * This stops the tracking of slider states, events and triggers of haptic feedback.
     */
    fun stop() = sliderTracker.stopTracking()

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
            sliderEventProducer.onStartTrackingTouch(seekBar)
        }
    }

    /** onProgressChanged event from the slider's [android.widget.SeekBar] */
    fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        if (isTracking) {
            sliderEventProducer.onProgressChanged(seekBar, progress, fromUser)
        }
    }

    /** onStopTrackingTouch event from the slider's [android.widget.SeekBar] */
    fun onStopTrackingTouch(seekBar: SeekBar) {
        if (isTracking) {
            sliderEventProducer.onStopTrackingTouch(seekBar)
        }
    }

    /** onArrowUp event recorded */
    fun onArrowUp() {
        if (isTracking) {
            sliderEventProducer.onArrowUp()
        }
    }

    /**
     * An external key was pressed (e.g., a volume key).
     *
     * This event is used to estimate the key-up event based on by running a timer as a waiting
     * coroutine in the [keyUpTimerScope]. A key-up event in a slider corresponds to an onArrowUp
     * event. Therefore, [onArrowUp] must be called after the timeout.
     */
    fun onKeyDown() {
        if (!isTracking) return

        if (isKeyUpTimerWaiting) {
            // Cancel the ongoing wait
            keyUpJob?.cancel()
        }
        keyUpJob =
            applicationScope.launch {
                delay(KEY_UP_TIMEOUT)
                onArrowUp()
            }
    }

    companion object {
        const val KEY_UP_TIMEOUT = 100L
    }
}
