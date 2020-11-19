/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test

import android.graphics.Point
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject.Companion.assertThat
import com.android.test.SurfaceViewBufferTestBase.Companion.ScalingMode
import com.android.test.SurfaceViewBufferTestBase.Companion.Transform
import junit.framework.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BufferRejectionTests(useBlastAdapter: Boolean) : SurfaceTracingTestBase(useBlastAdapter) {
    @Test
    fun testSetBuffersGeometry_0x0_rejectsBuffer() {
        val trace = withTrace {
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 100, 100,
                    R8G8B8A8_UNORM)
            it.mSurfaceProxy.ANativeWindowLock()
            it.mSurfaceProxy.ANativeWindowUnlockAndPost()
            it.mSurfaceProxy.ANativeWindowLock()
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 0, 0, R8G8B8A8_UNORM)
            // Submit buffer one with a different size which should be rejected
            it.mSurfaceProxy.ANativeWindowUnlockAndPost()

            // submit a buffer with the default buffer size
            it.mSurfaceProxy.ANativeWindowLock()
            it.mSurfaceProxy.ANativeWindowUnlockAndPost()
            it.mSurfaceProxy.waitUntilBufferDisplayed(3, 500 /* ms */)
        }
        // Verify we reject buffers since scaling mode == NATIVE_WINDOW_SCALING_MODE_FREEZE
        assertThat(trace).layer("SurfaceView", 2).doesNotExist()

        // Verify the next buffer is submitted with the correct size
        assertThat(trace).layer("SurfaceView", 3).also {
            it.hasBufferSize(defaultBufferSize)
            // scaling mode is not passed down to the layer for blast
            if (useBlastAdapter) {
                it.hasScalingMode(ScalingMode.SCALE_TO_WINDOW.ordinal)
            } else {
                it.hasScalingMode(ScalingMode.FREEZE.ordinal)
            }
        }
    }

    @Test
    fun testSetBufferScalingMode_freeze() {
        val bufferSize = Point(300, 200)
        val trace = withTrace {
            it.drawFrame()
            assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */), 0)
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))
            // Change buffer size and set scaling mode to freeze
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, Point(0, 0),
                    R8G8B8A8_UNORM)

            // first dequeued buffer does not have the new size so it should be rejected.
            it.mSurfaceProxy.SurfaceQueueBuffer(0)
            it.mSurfaceProxy.SurfaceSetScalingMode(ScalingMode.SCALE_TO_WINDOW)
            it.mSurfaceProxy.SurfaceQueueBuffer(1)
            assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed(3, 500 /* ms */), 0)
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
        assertThat(trace).layer("SurfaceView", 2).doesNotExist()
        assertThat(trace).layer("SurfaceView", 3).hasBufferSize(bufferSize)
    }

    @Test
    fun testSetBufferScalingMode_freeze_withBufferRotation() {
        val rotatedBufferSize = Point(defaultBufferSize.y, defaultBufferSize.x)
        val trace = withTrace {
            it.drawFrame()
            assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */), 0)
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, rotatedBufferSize,
                    R8G8B8A8_UNORM)
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))
            // Change buffer size and set scaling mode to freeze
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, Point(0, 0),
                    R8G8B8A8_UNORM)

            // first dequeued buffer does not have the new size so it should be rejected.
            it.mSurfaceProxy.SurfaceQueueBuffer(0)
            // add a buffer transform so the buffer size is correct.
            it.mSurfaceProxy.ANativeWindowSetBuffersTransform(Transform.ROT_90)
            it.mSurfaceProxy.SurfaceQueueBuffer(1)
            assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed(3, 500 /* ms */), 0)
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
        assertThat(trace).layer("SurfaceView", 2).doesNotExist()
        assertThat(trace).layer("SurfaceView", 3).hasBufferSize(rotatedBufferSize)
        assertThat(trace).layer("SurfaceView", 3).hasBufferOrientation(Transform.ROT_90.value)
    }

    @Test
    fun testRejectedBuffersAreReleased() {
        val bufferSize = Point(300, 200)
        val trace = withTrace {
            for (count in 0 until 5) {
                it.drawFrame()
                assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed((count * 3) + 1L,
                        500 /* ms */), 0)
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize,
                        R8G8B8A8_UNORM)
                assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
                assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))
                // Change buffer size and set scaling mode to freeze
                it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, Point(0, 0),
                        R8G8B8A8_UNORM)

                // first dequeued buffer does not have the new size so it should be rejected.
                it.mSurfaceProxy.SurfaceQueueBuffer(0)
                it.mSurfaceProxy.SurfaceSetScalingMode(ScalingMode.SCALE_TO_WINDOW)
                it.mSurfaceProxy.SurfaceQueueBuffer(1)
                assertEquals(it.mSurfaceProxy.waitUntilBufferDisplayed((count * 3) + 3L,
                        500 /* ms */), 0)
            }
        }

        for (count in 0 until 5) {
            assertThat(trace).layer("SurfaceView", (count * 3) + 1L)
                    .hasBufferSize(defaultBufferSize)
            assertThat(trace).layer("SurfaceView", (count * 3) + 2L)
                    .doesNotExist()
            assertThat(trace).layer("SurfaceView", (count * 3) + 3L)
                    .hasBufferSize(bufferSize)
        }
    }
}