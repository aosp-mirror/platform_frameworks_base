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
class GeometryTests(useBlastAdapter: Boolean) : SurfaceTracingTestBase(useBlastAdapter) {
    @Test
    fun testSetBuffersGeometry_0x0_resetsBufferSize() {
        val trace = withTrace {
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, 0, 0,
                    R8G8B8A8_UNORM)
            it.mSurfaceProxy.ANativeWindowLock()
            it.mSurfaceProxy.ANativeWindowUnlockAndPost()
            it.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
    }

    @Test
    fun testSetBuffersGeometry_smallerThanBuffer() {
        val bufferSize = Point(300, 200)
        val trace = withTrace {
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            it.drawFrame()
            it.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(bufferSize)
            it.hasLayerSize(defaultBufferSize)
            it.hasScalingMode(ScalingMode.SCALE_TO_WINDOW.ordinal)
        }
    }

    @Test
    fun testSetBuffersGeometry_largerThanBuffer() {
        val bufferSize = Point(3000, 2000)
        val trace = withTrace {
            it.mSurfaceProxy.ANativeWindowSetBuffersGeometry(it.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            it.drawFrame()
            it.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(bufferSize)
            it.hasLayerSize(defaultBufferSize)
            it.hasScalingMode(ScalingMode.SCALE_TO_WINDOW.ordinal)
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
    fun testSetBuffersTransform_FLIP() {
        val transforms = arrayOf(Transform.FLIP_H, Transform.FLIP_V, Transform.ROT_180).withIndex()
        for ((index, transform) in transforms) {
            val trace = withTrace {
                it.mSurfaceProxy.ANativeWindowSetBuffersTransform(transform)
                it.mSurfaceProxy.ANativeWindowLock()
                it.mSurfaceProxy.ANativeWindowUnlockAndPost()
                it.mSurfaceProxy.waitUntilBufferDisplayed(index + 1L, 500 /* ms */)
            }

            assertThat(trace).layer("SurfaceView", index + 1L).also {
                it.hasBufferSize(defaultBufferSize)
                it.hasLayerSize(defaultBufferSize)
                it.hasBufferOrientation(transform.value)
            }
        }
    }
}