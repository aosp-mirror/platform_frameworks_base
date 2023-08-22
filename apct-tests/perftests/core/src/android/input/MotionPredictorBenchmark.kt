/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.input

import android.content.Context
import android.content.res.Resources
import android.os.SystemProperties
import android.perftests.utils.PerfStatusReporter
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.MotionPredictor

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.LargeTest
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

import java.time.Duration

private fun getStylusMotionEvent(
        eventTime: Duration,
        action: Int,
        x: Float,
        y: Float,
        ): MotionEvent{
    val pointerCount = 1
    val properties = arrayOfNulls<MotionEvent.PointerProperties>(pointerCount)
    val coords = arrayOfNulls<MotionEvent.PointerCoords>(pointerCount)

    for (i in 0 until pointerCount) {
        properties[i] = PointerProperties()
        properties[i]!!.id = i
        properties[i]!!.toolType = MotionEvent.TOOL_TYPE_STYLUS
        coords[i] = PointerCoords()
        coords[i]!!.x = x
        coords[i]!!.y = y
    }

    return MotionEvent.obtain(/*downTime=*/0, eventTime.toMillis(), action, properties.size,
                properties, coords, /*metaState=*/0, /*buttonState=*/0,
                /*xPrecision=*/0f, /*yPrecision=*/0f, /*deviceId=*/0, /*edgeFlags=*/0,
                InputDevice.SOURCE_STYLUS, /*flags=*/0)
}

private fun getPredictionContext(offset: Duration, enablePrediction: Boolean): Context {
    val context = mock(Context::class.java)
    val resources: Resources = mock(Resources::class.java)
    `when`(context.getResources()).thenReturn(resources)
    `when`(resources.getInteger(
            com.android.internal.R.integer.config_motionPredictionOffsetNanos)).thenReturn(
                offset.toNanos().toInt())
    `when`(resources.getBoolean(
            com.android.internal.R.bool.config_enableMotionPrediction)).thenReturn(enablePrediction)
    return context
}

@RunWith(AndroidJUnit4::class)
@LargeTest
class MotionPredictorBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule
    val perfStatusReporter = PerfStatusReporter()
    private val initialPropertyValue =
            SystemProperties.get("persist.input.enable_motion_prediction")


    @Before
    fun setUp() {
        instrumentation.uiAutomation.executeShellCommand(
            "setprop persist.input.enable_motion_prediction true")
    }

    @After
    fun tearDown() {
        instrumentation.uiAutomation.executeShellCommand(
            "setprop persist.input.enable_motion_prediction $initialPropertyValue")
    }

    /**
     * In a typical usage, app will send the event to the predictor and then call .predict to draw
     * a prediction. In a loop, we keep sending MOVE and then calling .predict to simulate this.
     */
    @Test
    fun timeRecordAndPredict() {
        val offset = Duration.ofMillis(20)
        var eventTime = Duration.ofMillis(0)
        val eventInterval = Duration.ofMillis(4) // 240 Hz

        var eventPosition = 0f
        val positionInterval = 10f

        val predictor = MotionPredictor(getPredictionContext(offset, /*enablePrediction=*/true))
        // ACTION_DOWN t=0 x=0 y=0
        predictor.record(getStylusMotionEvent(
            eventTime, ACTION_DOWN, /*x=*/eventPosition, /*y=*/eventPosition))

        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            eventTime += eventInterval
            eventPosition += positionInterval

            // Send MOVE event and then call .predict
            val moveEvent = getStylusMotionEvent(
                eventTime, ACTION_MOVE, /*x=*/eventPosition, /*y=*/eventPosition)
            predictor.record(moveEvent)
            val predictionTime = eventTime + eventInterval
            val predicted = checkNotNull(predictor.predict(predictionTime.toNanos()))
            assertTrue(predicted.eventTime <= (predictionTime + offset).toMillis())
        }
    }

    /**
     * The creation of the predictor should happen infrequently. However, we still want to be
     * mindful of the load times.
     */
    @Test
    fun timeCreatePredictor() {
        val context = getPredictionContext(
                /*offset=*/Duration.ofMillis(20), /*enablePrediction=*/true)

        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            MotionPredictor(context)
        }
    }
}
