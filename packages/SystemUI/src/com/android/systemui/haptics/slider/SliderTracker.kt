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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Tracker component for a slider.
 *
 * The tracker maintains a state machine operated by slider events coming from a
 * [SliderEventProducer]. An action is executed in each state via a [SliderListener].
 *
 * @property[scope] [CoroutineScope] to launch the collection of [SliderEvent] and state machine
 *   logic.
 * @property[sliderListener] [SliderListener] to execute actions on a given [SliderState].
 * @property[eventProducer] Producer of [SliderEvent] to iterate over a state machine.
 */
sealed class SliderTracker(
    protected val scope: CoroutineScope,
    protected val sliderListener: SliderStateListener,
    protected val eventProducer: SliderEventProducer,
) {

    /* Reference to the current state of the internal state machine */
    var currentState: SliderState = SliderState.IDLE
        protected set

    /**
     * Job that launches and maintains the coroutine that collects events and operates the state
     * machine.
     */
    protected var job: Job? = null

    /** Indicator that the tracker is active and tracking */
    var isTracking = false
        get() = job != null && job?.isActive == true
        private set

    /** Starts the [Job] that collects slider events and runs the state machine */
    fun startTracking() {
        job =
            scope.launch {
                eventProducer.produceEvents().collect { event ->
                    iterateState(event)
                    executeOnState(currentState)
                }
            }
    }

    /** Stops the collection of slider events and the state machine */
    fun stopTracking() {
        job?.cancel("Stopped tracking slider state")
        job = null
        resetState()
    }

    /**
     * Iterate through the state machine due to a new slider event. As a result, the current state
     * is modified.
     *
     * @param[event] The slider event that is received.
     */
    protected abstract suspend fun iterateState(event: SliderEvent)

    /**
     * Execute an action based on the state of the state machine. This method should use the
     * [SliderListener] to act on the current state.
     *
     * @param[currentState] A [SliderState] in the state machine
     */
    protected abstract fun executeOnState(currentState: SliderState)

    /** Reset the state machine by setting the current state to [SliderState.IDLE] */
    protected open fun resetState() {
        currentState = SliderState.IDLE
    }
}
