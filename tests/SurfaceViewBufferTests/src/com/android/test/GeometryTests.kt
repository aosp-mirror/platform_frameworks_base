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

import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject.Companion.assertThat
import com.android.test.SurfaceViewBufferTestBase.Companion.ScalingMode
import com.android.test.SurfaceViewBufferTestBase.Companion.Transform
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(Parameterized::class)
class GeometryTests(useBlastAdapter: Boolean) : SurfaceTracingTestBase(useBlastAdapter) {
    @Test
    fun testSetBuffersGeometry_0x0_resetsBufferSize() {
        val trace = withTrace { activity ->
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, 0, 0,
                    R8G8B8A8_UNORM)
            activity.mSurfaceProxy.ANativeWindowLock()
            activity.mSurfaceProxy.ANativeWindowUnlockAndPost()
            activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
    }

    @Test
    fun testSetBuffersGeometry_smallerThanBuffer() {
        val bufferSize = Point(300, 200)
        val trace = withTrace { activity ->
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            activity.drawFrame()
            activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
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
        val trace = withTrace { activity ->
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            activity.drawFrame()
            activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
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
        val trace = withTrace { activity ->
            activity.drawFrame()
            assertEquals(activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */), 0)
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, bufferSize,
                    R8G8B8A8_UNORM)
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))
            // Change buffer size and set scaling mode to freeze
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, Point(0, 0),
                    R8G8B8A8_UNORM)

            // first dequeued buffer does not have the new size so it should be rejected.
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            activity.mSurfaceProxy.SurfaceSetScalingMode(ScalingMode.SCALE_TO_WINDOW)
            activity.mSurfaceProxy.SurfaceQueueBuffer(1)
            assertEquals(activity.mSurfaceProxy.waitUntilBufferDisplayed(3, 500 /* ms */), 0)
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
            val trace = withTrace { activity ->
                activity.mSurfaceProxy.ANativeWindowSetBuffersTransform(transform)
                activity.mSurfaceProxy.ANativeWindowLock()
                activity.mSurfaceProxy.ANativeWindowUnlockAndPost()
                activity.mSurfaceProxy.waitUntilBufferDisplayed(index + 1L, 500 /* ms */)
            }

            assertThat(trace).layer("SurfaceView", index + 1L).also {
                it.hasBufferSize(defaultBufferSize)
                it.hasLayerSize(defaultBufferSize)
                it.hasBufferOrientation(transform.value)
            }
        }
    }

    @Test
    fun testSurfaceViewResizeImmediatelyWithNonFreezeScaling() {
        val surfaceViewPosition = Rect()
        var trace = withTrace { activity ->
            activity.mSurfaceProxy.SurfaceSetScalingMode(ScalingMode.SCALE_TO_WINDOW)
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
            activity.mSurfaceProxy.drawBuffer(0, Color.BLUE)
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
            activity.mSurfaceView!!.getBoundsOnScreen(surfaceViewPosition)
        }

        runOnUiThread {
            val svBounds = Rect(0, 0, defaultBufferSize.x, defaultBufferSize.y)
            svBounds.offsetTo(surfaceViewPosition.left, surfaceViewPosition.top)
            checkPixels(svBounds, Color.BLUE)
        }

        // check that the layer and buffer starts with the default size
        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(defaultBufferSize)
            it.hasLayerSize(defaultBufferSize)
        }
        val newSize = Point(1280, 960)
        lateinit var resizeCountDownLatch: CountDownLatch
        runOnUiThread {
            resizeCountDownLatch = it.resizeSurfaceView(newSize)
        }
        assertTrue(resizeCountDownLatch.await(1000, TimeUnit.MILLISECONDS))
        // wait for sf to handle the resize transaction request
        SystemClock.sleep(500)
        trace = withTrace { _ ->
            // take a trace with the new size
        }

        // check that the layer size has changed and the buffer is now streched to the new layer
        // size
        runOnUiThread {
            val svBounds = Rect(0, 0, newSize.x, newSize.y)
            svBounds.offsetTo(surfaceViewPosition.left, surfaceViewPosition.top)
            checkPixels(svBounds, Color.BLUE)
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasLayerSize(newSize)
            it.hasBufferSize(defaultBufferSize)
        }
    }

    @Test
    fun testSurfaceViewDoesNotResizeWithDefaultScaling() {
        val surfaceViewPosition = Rect()
        var trace = withTrace { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
            activity.mSurfaceProxy.drawBuffer(0, Color.BLUE)
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */)
            activity.mSurfaceView!!.getBoundsOnScreen(surfaceViewPosition)
        }

        runOnUiThread {
            val svBounds = Rect(0, 0, defaultBufferSize.x, defaultBufferSize.y)
            svBounds.offsetTo(surfaceViewPosition.left, surfaceViewPosition.top)
            checkPixels(svBounds, Color.BLUE)
        }

        // check that the layer and buffer starts with the default size
        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasBufferSize(defaultBufferSize)
            it.hasLayerSize(defaultBufferSize)
        }
        val newSize = Point(1280, 960)
        lateinit var resizeCountDownLatch: CountDownLatch
        runOnUiThread {
            resizeCountDownLatch = it.resizeSurfaceView(newSize)
        }
        assertTrue(resizeCountDownLatch.await(1000, TimeUnit.MILLISECONDS))
        // wait for sf to handle the resize transaction request
        SystemClock.sleep(500)
        trace = withTrace { _ ->
            // take a trace after the size change
        }

        // check the layer and buffer remains the same size
        runOnUiThread {
            val svBounds = Rect(0, 0, defaultBufferSize.x, defaultBufferSize.y)
            svBounds.offsetTo(surfaceViewPosition.left, surfaceViewPosition.top)
            checkPixels(svBounds, Color.BLUE)
        }

        assertThat(trace).layer("SurfaceView", 1).also {
            it.hasLayerSize(defaultBufferSize)
            it.hasBufferSize(defaultBufferSize)
        }
    }
}