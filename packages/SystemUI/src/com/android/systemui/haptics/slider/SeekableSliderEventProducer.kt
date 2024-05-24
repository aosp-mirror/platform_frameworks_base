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

import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** An event producer for a Seekable element such as the Android [SeekBar] */
class SeekableSliderEventProducer : SliderEventProducer, OnSeekBarChangeListener {

    /** The current event reported by a SeekBar */
    private val _currentEvent = MutableStateFlow(SliderEvent(SliderEventType.NOTHING, 0f))

    override fun produceEvents(): Flow<SliderEvent> = _currentEvent.asStateFlow()

    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
        val eventType =
            if (fromUser) SliderEventType.PROGRESS_CHANGE_BY_USER
            else SliderEventType.PROGRESS_CHANGE_BY_PROGRAM

        _currentEvent.value = SliderEvent(eventType, normalizeProgress(seekBar, progress))
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

    override fun onStartTrackingTouch(seekBar: SeekBar) {
        _currentEvent.update { previousEvent ->
            SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, previousEvent.currentProgress)
        }
    }

    override fun onStopTrackingTouch(seekBar: SeekBar) {
        _currentEvent.update { previousEvent ->
            SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, previousEvent.currentProgress)
        }
    }

    /** The arrow navigation that was operating the slider has stopped. */
    fun onArrowUp() {
        _currentEvent.update { previousEvent ->
            SliderEvent(SliderEventType.ARROW_UP, previousEvent.currentProgress)
        }
    }
}
