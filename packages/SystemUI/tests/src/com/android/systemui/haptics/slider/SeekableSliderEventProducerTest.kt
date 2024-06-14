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
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import junit.framework.Assert.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SeekableSliderEventProducerTest : SysuiTestCase() {

    private val seekBar = SeekBar(mContext)
    private val eventProducer = SeekableSliderEventProducer()
    private val eventFlow = eventProducer.produceEvents()

    @Test
    fun onStartTrackingTouch_noProgress_trackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStartTrackingTouch(seekBar)

        assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, 0F), latest)
    }

    @Test
    fun onStopTrackingTouch_noProgress_StoppedTrackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStopTrackingTouch(seekBar)

        assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0F), latest)
    }

    @Test
    fun onProgressChangeByUser_changeByUserEventProduced_withNormalizedProgress() = runTest {
        val progress = 50
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(seekBar, progress, true)

        assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, 0.5F), latest)
    }

    @Test
    fun onProgressChangeByUser_zeroWidthSlider_changeByUserEventProduced_withMaxProgress() =
        runTest {
            // No-width slider where the min and max values are the same
            seekBar.min = 100
            seekBar.max = 100
            val progress = 50
            val latest by collectLastValue(eventFlow)

            eventProducer.onProgressChanged(seekBar, progress, true)

            assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, 1.0F), latest)
        }

    @Test
    fun onProgressChangeByProgram_changeByProgramEventProduced_withNormalizedProgress() = runTest {
        val progress = 50
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(seekBar, progress, false)

        assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, 0.5F), latest)
    }

    @Test
    fun onProgressChangeByProgram_zeroWidthSlider_changeByProgramEventProduced_withMaxProgress() =
        runTest {
            // No-width slider where the min and max values are the same
            seekBar.min = 100
            seekBar.max = 100
            val progress = 50
            val latest by collectLastValue(eventFlow)

            eventProducer.onProgressChanged(seekBar, progress, false)

            assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, 1.0F), latest)
        }

    @Test
    fun onStartTrackingTouch_afterProgress_trackingTouchEventProduced_withNormalizedProgress() =
        runTest {
            val progress = 50
            val latest by collectLastValue(eventFlow)

            eventProducer.onProgressChanged(seekBar, progress, true)
            eventProducer.onStartTrackingTouch(seekBar)

            assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, 0.5F), latest)
        }

    @Test
    fun onStopTrackingTouch_afterProgress_stopTrackingTouchEventProduced_withNormalizedProgress() =
        runTest {
            val progress = 50
            val latest by collectLastValue(eventFlow)

            eventProducer.onProgressChanged(seekBar, progress, true)
            eventProducer.onStopTrackingTouch(seekBar)

            assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0.5F), latest)
        }

    @Test
    fun onArrowUp_afterStartTrackingTouch_ArrowUpProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStartTrackingTouch(seekBar)
        eventProducer.onArrowUp()

        assertEquals(SliderEvent(SliderEventType.ARROW_UP, 0f), latest)
    }

    @Test
    fun onArrowUp_afterChangeByProgram_ArrowUpProduced_withProgress() = runTest {
        val progress = 50
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(seekBar, progress, false)
        eventProducer.onArrowUp()

        assertEquals(SliderEvent(SliderEventType.ARROW_UP, 0.5f), latest)
    }
}
