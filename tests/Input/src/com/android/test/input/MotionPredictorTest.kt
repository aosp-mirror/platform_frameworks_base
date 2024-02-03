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

package com.android.test.input

import android.content.Context
import android.content.res.Resources
import android.os.SystemProperties
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties
import android.view.MotionPredictor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
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
@SmallTest
class MotionPredictorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    val initialPropertyValue = SystemProperties.get("persist.input.enable_motion_prediction")

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
     * a prediction. Here, we send 2 events to the predictor and check the returned event.
     * Input:
     * t = 0 x = 0 y = 0
     * t = 4 x = 10 y = 20
     * Output (expected):
     * t = 12 x = 30 y = 60 ± error
     *
     * Historical data is ignored for simplicity.
     */
    @Test
    fun testPredictedCoordinatesAndTime() {
        val context = getPredictionContext(
            /*offset=*/Duration.ofMillis(1), /*enablePrediction=*/true)
        val predictor = MotionPredictor(context)
        var eventTime = Duration.ofMillis(0)
        val downEvent = getStylusMotionEvent(eventTime, ACTION_DOWN, /*x=*/0f, /*y=*/0f)
        // ACTION_DOWN t=0 x=0 y=0
        predictor.record(downEvent)

        eventTime += Duration.ofMillis(4)
        val moveEvent = getStylusMotionEvent(eventTime, ACTION_MOVE, /*x=*/10f, /*y=*/20f)
        // ACTION_MOVE t=1 x=1 y=2
        predictor.record(moveEvent)

        val predicted = predictor.predict(Duration.ofMillis(8).toNanos())
        assertNotNull(predicted)

        // Prediction will happen for t=12 (since it is the next input interval after the requested
        // time, 8, plus the model offset, 1).
        assertEquals(12, predicted!!.eventTime)
        assertEquals(30f, predicted.x, /*delta=*/10f)
        assertEquals(60f, predicted.y, /*delta=*/15f)
    }
}
