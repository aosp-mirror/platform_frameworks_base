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
class SliderStateProducerTest : SysuiTestCase() {

    private val eventProducer = SliderStateProducer()
    private val eventFlow = eventProducer.produceEvents()

    @Test
    fun onStartTrackingTouch_noProgress_trackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStartTracking(/*fromUser =*/ true)

        assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, 0F), latest)
    }

    @Test
    fun onStopTrackingTouch_noProgress_StoppedTrackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStopTracking(/*fromUser =*/ true)

        assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, 0F), latest)
    }

    @Test
    fun onStartTrackingProgram_noProgress_trackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStartTracking(/*fromUser =*/ false)

        assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_PROGRAM, 0F), latest)
    }

    @Test
    fun onStopTrackingProgram_noProgress_StoppedTrackingTouchEventProduced() = runTest {
        val latest by collectLastValue(eventFlow)

        eventProducer.onStopTracking(/*fromUser =*/ false)

        assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_PROGRAM, 0F), latest)
    }

    @Test
    fun onProgressChangeByUser_changeByUserEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ true, progress)

        assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_USER, progress), latest)
    }

    @Test
    fun onProgressChangeByProgram_changeByProgramEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ false, progress)

        assertEquals(SliderEvent(SliderEventType.PROGRESS_CHANGE_BY_PROGRAM, progress), latest)
    }

    @Test
    fun onStartTrackingTouch_afterProgress_trackingTouchEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ true, progress)
        eventProducer.onStartTracking(/*fromUser =*/ true)

        assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_TOUCH, progress), latest)
    }

    @Test
    fun onStopTrackingTouch_afterProgress_stopTrackingTouchEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ true, progress)
        eventProducer.onStopTracking(/*fromUser =*/ true)

        assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_TOUCH, progress), latest)
    }

    @Test
    fun onStartTrackingProgram_afterProgress_trackingProgramEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ false, progress)
        eventProducer.onStartTracking(/*fromUser =*/ false)

        assertEquals(SliderEvent(SliderEventType.STARTED_TRACKING_PROGRAM, progress), latest)
    }

    @Test
    fun onStopTrackingProgram_afterProgress_stopTrackingProgramEventProduced() = runTest {
        val progress = 0.5f
        val latest by collectLastValue(eventFlow)

        eventProducer.onProgressChanged(/*fromUser =*/ false, progress)
        eventProducer.onStopTracking(/*fromUser =*/ false)

        assertEquals(SliderEvent(SliderEventType.STOPPED_TRACKING_PROGRAM, progress), latest)
    }
}
