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

import androidx.annotation.FloatRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A stateful producer of [SliderEvent] */
class SliderStateProducer : SliderEventProducer {

    /** The current event of a slider */
    private val _currentEvent = MutableStateFlow(SliderEvent(SliderEventType.NOTHING, 0f))

    override fun produceEvents(): Flow<SliderEvent> = _currentEvent.asStateFlow()

    fun onProgressChanged(fromUser: Boolean, @FloatRange(from = 0.0, to = 1.0) progress: Float) {
        val eventType =
            if (fromUser) SliderEventType.PROGRESS_CHANGE_BY_USER
            else SliderEventType.PROGRESS_CHANGE_BY_PROGRAM

        _currentEvent.value = SliderEvent(eventType, progress)
    }

    fun onStartTracking(fromUser: Boolean) {
        val eventType =
            if (fromUser) SliderEventType.STARTED_TRACKING_TOUCH
            else SliderEventType.STARTED_TRACKING_PROGRAM
        _currentEvent.update { previousEvent ->
            SliderEvent(eventType, previousEvent.currentProgress)
        }
    }

    fun onStopTracking(fromUser: Boolean) {
        val eventType =
            if (fromUser) SliderEventType.STOPPED_TRACKING_TOUCH
            else SliderEventType.STOPPED_TRACKING_PROGRAM
        _currentEvent.update { previousEvent ->
            SliderEvent(eventType, previousEvent.currentProgress)
        }
    }

    fun resetWithProgress(@FloatRange(from = 0.0, to = 1.0) progress: Float) {
        _currentEvent.value = SliderEvent(SliderEventType.NOTHING, progress)
    }
}
