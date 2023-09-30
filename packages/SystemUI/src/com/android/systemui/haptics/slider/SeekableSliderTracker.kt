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
 */

package com.android.systemui.haptics.slider

import androidx.annotation.VisibleForTesting
import com.android.systemui.dagger.qualifiers.Main
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Slider tracker attached to a seekable slider.
 *
 * The tracker runs a state machine to execute actions on touch-based events typical of a seekable
 * slider such as [android.widget.SeekBar]. Coroutines responsible for running the state machine,
 * collecting slider events and maintaining waiting states are run on the main thread via the
 * [com.android.systemui.dagger.qualifiers.Main] coroutine dispatcher.
 *
 * @param[sliderStateListener] Listener of the slider state.
 * @param[sliderEventProducer] Producer of slider events arising from the slider.
 * @param[mainDispatcher] [CoroutineDispatcher] used to launch coroutines for the collection of
 *   slider events and the launch of timer jobs.
 * @property[config] Configuration parameters of the slider tracker.
 */
class SeekableSliderTracker(
    sliderStateListener: SliderStateListener,
    sliderEventProducer: SliderEventProducer,
    @Main mainDispatcher: CoroutineDispatcher,
    private val config: SeekableSliderTrackerConfig = SeekableSliderTrackerConfig(),
) : SliderTracker(CoroutineScope(mainDispatcher), sliderStateListener, sliderEventProducer) {

    // History of the latest progress collected from slider events
    private var latestProgress = 0f
    // Timer job for the wait state
    private var timerJob: Job? = null
    // Indicator that there is waiting job active
    var isWaiting = false
        private set
        get() = timerJob != null && timerJob?.isActive == true

    override suspend fun iterateState(event: SliderEvent) {
        when (currentState) {
            SliderState.IDLE -> handleIdle(event.type)
            SliderState.WAIT -> handleWait(event.type, event.currentProgress)
            SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH -> handleAcquired(event.type)
            SliderState.DRAG_HANDLE_DRAGGING -> handleDragging(event.type, event.currentProgress)
            SliderState.DRAG_HANDLE_REACHED_BOOKEND ->
                handleReachedBookend(event.type, event.currentProgress)
            SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH -> setState(SliderState.IDLE)
            SliderState.JUMP_TRACK_LOCATION_SELECTED -> handleJumpToTrack(event.type)
            SliderState.JUMP_BOOKEND_SELECTED -> handleJumpToBookend(event.type)
        }
        latestProgress = event.currentProgress
    }

    private fun handleIdle(newEventType: SliderEventType) {
        if (newEventType == SliderEventType.STARTED_TRACKING_TOUCH) {
            timerJob = launchTimer()
            // The WAIT state will wait for the timer to complete or a slider progress to occur.
            // This will disambiguate between an imprecise touch that acquires the slider handle,
            // and a select and jump operation in the slider track.
            setState(SliderState.WAIT)
        }
    }

    private fun launchTimer() =
        scope.launch {
            delay(config.waitTimeMillis)
            if (isActive && currentState == SliderState.WAIT) {
                setState(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)
                // This transitory state must also trigger the corresponding action
                executeOnState(currentState)
            }
        }

    private fun handleWait(newEventType: SliderEventType, currentProgress: Float) {
        // The timer may have completed and may have already modified the state
        if (currentState != SliderState.WAIT) return

        // The timer is still running but the state may be modified by the progress change
        val deltaProgressIsJump = deltaProgressIsAboveThreshold(currentProgress)
        if (newEventType == SliderEventType.PROGRESS_CHANGE_BY_USER) {
            if (bookendReached(currentProgress)) {
                setState(SliderState.JUMP_BOOKEND_SELECTED)
            } else if (deltaProgressIsJump) {
                setState(SliderState.JUMP_TRACK_LOCATION_SELECTED)
            } else {
                setState(SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH)
            }
        } else if (newEventType == SliderEventType.STOPPED_TRACKING_TOUCH) {
            setState(SliderState.IDLE)
        }

        // If the state changed, the timer does not need to complete. No further synchronization
        // will be required onwards until WAIT is reached again.
        if (currentState != SliderState.WAIT) {
            timerJob?.cancel()
            timerJob = null
        }
    }

    private fun handleAcquired(newEventType: SliderEventType) {
        if (newEventType == SliderEventType.STOPPED_TRACKING_TOUCH) {
            setState(SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH)
        } else if (newEventType == SliderEventType.PROGRESS_CHANGE_BY_USER) {
            setState(SliderState.DRAG_HANDLE_DRAGGING)
        }
    }

    private fun handleDragging(newEventType: SliderEventType, currentProgress: Float) {
        if (newEventType == SliderEventType.STOPPED_TRACKING_TOUCH) {
            setState(SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH)
        } else if (
            newEventType == SliderEventType.PROGRESS_CHANGE_BY_USER &&
                bookendReached(currentProgress)
        ) {
            setState(SliderState.DRAG_HANDLE_REACHED_BOOKEND)
        }
    }

    private fun handleReachedBookend(newEventType: SliderEventType, currentProgress: Float) {
        if (newEventType == SliderEventType.PROGRESS_CHANGE_BY_USER) {
            if (!bookendReached(currentProgress)) {
                setState(SliderState.DRAG_HANDLE_DRAGGING)
            }
        } else if (newEventType == SliderEventType.STOPPED_TRACKING_TOUCH) {
            setState(SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH)
        }
    }

    private fun handleJumpToTrack(newEventType: SliderEventType) {
        when (newEventType) {
            SliderEventType.PROGRESS_CHANGE_BY_USER -> setState(SliderState.DRAG_HANDLE_DRAGGING)
            SliderEventType.STOPPED_TRACKING_TOUCH ->
                setState(SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH)
            else -> {}
        }
    }

    private fun handleJumpToBookend(newEventType: SliderEventType) {
        when (newEventType) {
            SliderEventType.PROGRESS_CHANGE_BY_USER -> setState(SliderState.DRAG_HANDLE_DRAGGING)
            SliderEventType.STOPPED_TRACKING_TOUCH ->
                setState(SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH)
            else -> {}
        }
    }

    override fun executeOnState(currentState: SliderState) {
        when (currentState) {
            SliderState.DRAG_HANDLE_ACQUIRED_BY_TOUCH -> sliderListener.onHandleAcquiredByTouch()
            SliderState.DRAG_HANDLE_RELEASED_FROM_TOUCH -> {
                sliderListener.onHandleReleasedFromTouch()
                // This transitory state must also reset the state machine
                resetState()
            }
            SliderState.DRAG_HANDLE_DRAGGING -> sliderListener.onProgress(latestProgress)
            SliderState.DRAG_HANDLE_REACHED_BOOKEND -> executeOnBookend()
            SliderState.JUMP_TRACK_LOCATION_SELECTED ->
                sliderListener.onProgressJump(latestProgress)
            else -> {}
        }
    }

    private fun executeOnBookend() {
        if (latestProgress >= config.upperBookendThreshold) sliderListener.onUpperBookend()
        else sliderListener.onLowerBookend()
    }

    override fun resetState() {
        timerJob?.cancel()
        timerJob = null
        super.resetState()
    }

    private fun deltaProgressIsAboveThreshold(
        currentProgress: Float,
        epsilon: Float = 0.00001f,
    ): Boolean {
        val delta = abs(currentProgress - latestProgress)
        return delta > config.jumpThreshold - epsilon
    }

    private fun bookendReached(currentProgress: Float): Boolean {
        return currentProgress >= config.upperBookendThreshold ||
            currentProgress <= config.lowerBookendThreshold
    }

    @VisibleForTesting
    fun setState(state: SliderState) {
        currentState = state
    }
}
