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

package android.animation

import android.animation.AnimatorTestRuleToolkit.Companion.TAG
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import platform.test.motion.MotionTestRule
import platform.test.motion.RecordedMotion
import platform.test.motion.RecordedMotion.Companion.create
import platform.test.motion.golden.DataPoint
import platform.test.motion.golden.Feature
import platform.test.motion.golden.FrameId
import platform.test.motion.golden.TimeSeries
import platform.test.motion.golden.TimeSeriesCaptureScope
import platform.test.motion.golden.TimestampFrameId
import platform.test.screenshot.captureToBitmapAsync

class AnimatorTestRuleToolkit(
    internal val animatorTestRule: AnimatorTestRule,
    internal val testScope: TestScope,
    internal val currentActivityScenario: () -> ActivityScenario<*>,
) {
    internal companion object {
        const val TAG = "AnimatorRuleToolkit"
    }
}

/** Capture utility to extract a [Bitmap] from a [drawable]. */
fun captureDrawable(drawable: Drawable): Bitmap {
    val width = drawable.bounds.right - drawable.bounds.left
    val height = drawable.bounds.bottom - drawable.bounds.top

    // If either dimension is 0 this will fail, so we set it to 1 pixel instead.
    return drawable.toBitmap(
        width =
        if (width > 0) {
            width
        } else {
            1
        },
        height =
        if (height > 0) {
            height
        } else {
            1
        },
    )
}

/** Capture utility to extract a [Bitmap] from a [view]. */
fun captureView(view: View): Bitmap {
    return view.captureToBitmapAsync().get(10, TimeUnit.SECONDS)
}

/**
 * Controls the timing of the motion recording.
 *
 * The time series is recorded while the [recording] function is running.
 */
class MotionControl(val recording: MotionControlFn)

typealias MotionControlFn = suspend MotionControlScope.() -> Unit

interface MotionControlScope {
    /** Waits until [check] returns true. Invoked on each frame. */
    suspend fun awaitCondition(check: () -> Boolean)

    /** Waits for [count] frames to be processed. */
    suspend fun awaitFrames(count: Int = 1)
}

/** Defines the sampling of features during a test run. */
data class AnimatorRuleRecordingSpec<T>(
    /** The root `observing` object, available in [timeSeriesCapture]'s [TimeSeriesCaptureScope]. */
    val captureRoot: T,

    /** The timing for the recording. */
    val motionControl: MotionControl,

    /** Time interval between frame captures, in milliseconds. */
    val frameDurationMs: Long = 16L,

    /** Whether a sequence of screenshots should also be recorded. */
    val visualCapture: ((captureRoot: T) -> Bitmap)? = null,

    /** Produces the time-series, invoked on each animation frame. */
    val timeSeriesCapture: TimeSeriesCaptureScope<T>.() -> Unit,
)

/** Records the time-series of the features specified in [recordingSpec]. */
fun <T> MotionTestRule<AnimatorTestRuleToolkit>.recordMotion(
    recordingSpec: AnimatorRuleRecordingSpec<T>
): RecordedMotion {
    with(toolkit.animatorTestRule) {
        val activityScenario = toolkit.currentActivityScenario()
        val frameIdCollector = mutableListOf<FrameId>()
        val propertyCollector = mutableMapOf<String, MutableList<DataPoint<*>>>()
        val screenshotCollector =
            if (recordingSpec.visualCapture != null) {
                mutableListOf<Bitmap>()
            } else {
                null
            }

        fun recordFrame(frameId: FrameId) {
            Log.i(TAG, "recordFrame($frameId)")
            frameIdCollector.add(frameId)
            activityScenario.onActivity {
                recordingSpec.timeSeriesCapture.invoke(
                    TimeSeriesCaptureScope(recordingSpec.captureRoot, propertyCollector)
                )
            }

            val bitmap = recordingSpec.visualCapture?.invoke(recordingSpec.captureRoot)
            if (bitmap != null) screenshotCollector!!.add(bitmap)
        }

        val motionControl =
            MotionControlImpl(
                toolkit.animatorTestRule,
                toolkit.testScope,
                recordingSpec.frameDurationMs,
                recordingSpec.motionControl,
            )

        Log.i(TAG, "recordMotion() begin recording")

        var startFrameTime: Long? = null
        toolkit.currentActivityScenario().onActivity { startFrameTime = currentTime }
        while (!motionControl.recordingEnded) {
            var time: Long? = null
            toolkit.currentActivityScenario().onActivity { time = currentTime }
            recordFrame(TimestampFrameId(time!! - startFrameTime!!))
            toolkit.currentActivityScenario().onActivity { motionControl.nextFrame() }
        }

        Log.i(TAG, "recordMotion() end recording")

        val timeSeries =
            TimeSeries(
                frameIdCollector.toList(),
                propertyCollector.entries.map { entry -> Feature(entry.key, entry.value) },
            )

        return create(timeSeries, screenshotCollector)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class MotionControlImpl(
    val animatorTestRule: AnimatorTestRule,
    val testScope: TestScope,
    val frameMs: Long,
    motionControl: MotionControl,
) : MotionControlScope {
    private val recordingJob = motionControl.recording.launch()

    private val frameEmitter = MutableStateFlow<Long>(0)
    private val onFrame = frameEmitter.asStateFlow()

    var recordingEnded: Boolean = false

    fun nextFrame() {
        animatorTestRule.advanceTimeBy(frameMs)

        frameEmitter.tryEmit(animatorTestRule.currentTime)
        testScope.runCurrent()

        if (recordingJob.isCompleted) {
            recordingEnded = true
        }
    }

    override suspend fun awaitCondition(check: () -> Boolean) {
        onFrame.takeWhile { !check() }.collect {}
    }

    override suspend fun awaitFrames(count: Int) {
        onFrame.take(count).collect {}
    }

    private fun MotionControlFn.launch(): Job {
        val function = this
        return testScope.launch { function() }
    }
}
