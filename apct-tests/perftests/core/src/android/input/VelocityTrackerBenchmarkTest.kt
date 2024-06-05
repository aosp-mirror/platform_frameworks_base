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

import java.time.Duration

import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Helper class to maintain [MotionEvent]s for tests.
 *
 * This is primarily used to create [MotionEvent]s for tests, in a way where a sequence of
 * [MotionEvent]s created in multiple test runs are exactly the same, as long as [reset] is called
 * between consecutive sequences of [MotionEvent]s.
 *
 * Furthermore, it also contains convenience methods to run any queries/verifications of the
 * generated [MotionEvent]s.
 */
abstract class MotionState {
    /** Current time, in ms. */
    protected var currentTime = START_TIME

    /** Resets the state of this instance. */
    open fun reset() {
        currentTime = START_TIME
    }

    /** Creates a [MotionEvent]. */
    abstract fun createMotionEvent(): MotionEvent

    /** Asserts that the current velocity is not zero, just for verifying there's motion. */
    abstract fun assertNonZeroVelocity(velocityTracker: VelocityTracker)

    companion object {
        /** Arbitrarily chosen start time. */
        val START_TIME = Duration.ofMillis(100)
        /**
         * A small enough time jump, which won't be considered by the tracker as big enough to
         * deduce that a pointer has stopped.
         */
        val DEFAULT_TIME_JUMP = Duration.ofMillis(2)
    }
}

/** An implementation of [MotionState] for [MotionEvent.AXIS_SCROLL]. */
private class ScrollMotionState : MotionState() {
    override fun createMotionEvent(): MotionEvent {
        val props = MotionEvent.PointerProperties()
        props.id = 0
        val coords = MotionEvent.PointerCoords()
        coords.setAxisValue(MotionEvent.AXIS_SCROLL, DEFAULT_SCROLL_AMOUNT)
        val motionEvent = MotionEvent.obtain(
            /*downTime=*/0,
            currentTime.toMillis(),
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

        currentTime = currentTime.plus(DEFAULT_TIME_JUMP)

        return motionEvent
    }

    override fun assertNonZeroVelocity(velocityTracker: VelocityTracker) {
        Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL) != 0f)
    }

    companion object {
        private val DEFAULT_SCROLL_AMOUNT: Float = 30f
    }
}

/** An implementation of [MotionState] for [MotionEvent.AXIS_X] and [MotionEvent.AXIS_Y]. */
private class PlanarMotionState : MotionState() {
    private var x: Float = DEFAULT_X
    private var y: Float = DEFAULT_Y
    private var downEventCreated = false

    override fun createMotionEvent(): MotionEvent {
        val action: Int = if (downEventCreated) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_DOWN
        val motionEvent = MotionEvent.obtain(
            /*downTime=*/START_TIME.toMillis(),
            currentTime.toMillis(),
            action,
            x,
            y,
            /*metaState=*/0)

        if (downEventCreated) {
            x += INCREMENT
            y += INCREMENT
        } else {
            downEventCreated = true
        }
        currentTime = currentTime.plus(DEFAULT_TIME_JUMP)

        return motionEvent
    }

    override fun assertNonZeroVelocity(velocityTracker: VelocityTracker) {
        Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_X) != 0f)
        Assert.assertTrue(velocityTracker.getAxisVelocity(MotionEvent.AXIS_Y) != 0f)
    }

    override fun reset() {
        super.reset()
        x = DEFAULT_X
        y = DEFAULT_Y
        downEventCreated = false
    }

    companion object {
        /** Arbitrarily chosen constants. No need to have varying velocity for now. */
        private val DEFAULT_X: Float = 2f
        private val DEFAULT_Y: Float = 4f
        private val INCREMENT: Float = 0.7f
    }
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
        testAddMovement(ScrollMotionState())
    }

    @Test
    fun computeCurrentVelocity_computeAfterAllAdditions_axisScroll() {
        testComputeCurrentVelocity_computeAfterAllAdditions(ScrollMotionState())
    }

    @Test
    fun computeCurrentVelocity_computeAfterEachAdd_axisScroll() {
        testComputeCurrentVelocity_computeAfterEachAdd(ScrollMotionState())
    }

    @Test
    fun addMovement_planarAxes() {
        testAddMovement(PlanarMotionState())
    }

    @Test
    fun computeCurrentVelocity_computeAfterAllAdditions_planarAxes() {
        testComputeCurrentVelocity_computeAfterAllAdditions(PlanarMotionState())
    }

    private fun testAddMovement(motionState: MotionState) {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            state.pauseTiming()
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                val motionEvent = motionState.createMotionEvent()
                state.resumeTiming()
                velocityTracker.addMovement(motionEvent)
                state.pauseTiming()
            }
            velocityTracker.computeCurrentVelocity(1000)
            motionState.assertNonZeroVelocity(velocityTracker)
            // Clear the tracker for the next run
            velocityTracker.clear()
            motionState.reset()
            state.resumeTiming()
        }
    }

    private fun testComputeCurrentVelocity_computeAfterAllAdditions(motionState: MotionState) {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            // Add the data points
            state.pauseTiming()
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                velocityTracker.addMovement(motionState.createMotionEvent())
            }

            // Do the velocity computation
            state.resumeTiming()
            velocityTracker.computeCurrentVelocity(1000)

            state.pauseTiming()
            motionState.assertNonZeroVelocity(velocityTracker)
            // Clear the tracker for the next run
            velocityTracker.clear()
            state.resumeTiming()
        }
    }

    private fun testComputeCurrentVelocity_computeAfterEachAdd(motionState: MotionState) {
        val state = perfStatusReporter.getBenchmarkState()
        while (state.keepRunning()) {
            state.pauseTiming()
            for (i in 0 until TEST_NUM_DATAPOINTS) {
                velocityTracker.addMovement(motionState.createMotionEvent())
                state.resumeTiming()
                velocityTracker.computeCurrentVelocity(1000)
                state.pauseTiming()
            }
            motionState.assertNonZeroVelocity(velocityTracker)
            // Clear the tracker for the next run
            velocityTracker.clear()
            state.resumeTiming()
        }
    }

    companion object {
        private const val TEST_NUM_DATAPOINTS = 100
    }
}