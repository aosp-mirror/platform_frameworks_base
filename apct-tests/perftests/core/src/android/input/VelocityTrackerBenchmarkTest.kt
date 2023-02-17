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
package android.input

import android.perftests.utils.PerfStatusReporter
import android.view.InputDevice
import android.view.MotionEvent
import android.view.VelocityTracker

import androidx.test.filters.LargeTest
import androidx.test.runner.AndroidJUnit4

import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private fun createScrollMotionEvent(scrollAmount: Float, eventTimeMs: Long): MotionEvent {
    val props = MotionEvent.PointerProperties()
    props.id = 0
    val coords = MotionEvent.PointerCoords()
    coords.setAxisValue(MotionEvent.AXIS_SCROLL, scrollAmount)
    return MotionEvent.obtain(
        /*downTime=*/0,
        eventTimeMs,
        MotionEvent.ACTION_SCROLL,
        /*pointerCount=*/1,
        arrayOf(props),
        arrayOf(coords),
        /*metaState=*/0,
        /*buttonState=*/0,
        /*xPrecision=*/0f,
        /*yPrecision=*/0f,
        /*deviceId=*/1,
        /*edgeFlags=*/0,
        InputDevice.SOURCE_ROTARY_ENCODER,
        /*flags=*/0
    )
}

/**
 * Benchmark tests for [VelocityTracker]
 *
 * Build/Install/Run:
 * atest VelocityTrackerBenchmarkTest
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class VelocityTrackerBenchmarkTest {
    @get:Rule
    val perfStatusReporter: PerfStatusReporter = PerfStatusReporter()

    private val velocityTracker = VelocityTracker.obtain()
    @Before
    fun setup() {
        velocityTracker.clear()
    }

    @Test
    fun addMovement_axisScroll() {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            state.pauseTiming()
            var eventTimeMs: Long = 100
            val scrollAmount = 30f
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                state.resumeTiming()
                velocityTracker.addMovement(createScrollMotionEvent(scrollAmount, eventTimeMs))
                state.pauseTiming()
                eventTimeMs += 2
            }
            velocityTracker.computeCurrentVelocity(1000)
            Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL) > 0)
            // Clear the tracker for the next run
            velocityTracker.clear()
            state.resumeTiming()
        }
    }

    @Test
    fun computeCurrentVelocity_constantVelocity_axisScroll_computeAfterAllAdditions() {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            // Add the data points
            state.pauseTiming()
            var eventTimeMs: Long = 100
            val scrollAmount = 30f
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                velocityTracker.addMovement(createScrollMotionEvent(scrollAmount, eventTimeMs))
                eventTimeMs += 2
            }

            // Do the velocity computation
            state.resumeTiming()
            velocityTracker.computeCurrentVelocity(1000)

            // Clear the tracker for the next run
            state.pauseTiming()
            Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL) > 0)
            velocityTracker.clear()
            state.resumeTiming()
        }
    }

    @Test
    fun computeCurrentVelocity_constantVelocity_axisScroll_computeAfterEachAdd() {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            state.pauseTiming()
            var eventTimeMs: Long = 100
            val scrollAmount = 30f
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                velocityTracker.addMovement(createScrollMotionEvent(scrollAmount, eventTimeMs))
                state.resumeTiming()
                velocityTracker.computeCurrentVelocity(1000)
                state.pauseTiming()
                eventTimeMs += 2
            }
            Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL) > 0)
            // Clear the tracker for the next run
            velocityTracker.clear()
            state.resumeTiming()
        }
    }

    companion object {
        private const val TEST_NUM_DATAPOINTS = 100
    }
}