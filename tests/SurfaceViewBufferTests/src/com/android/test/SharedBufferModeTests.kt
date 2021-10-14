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
import android.graphics.Rect
import com.android.server.wm.flicker.traces.layers.LayersTraceSubject.Companion.assertThat
import junit.framework.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SharedBufferModeTests(useBlastAdapter: Boolean) : SurfaceTracingTestBase(useBlastAdapter) {
    /** Sanity test to check each buffer is presented if its submitted with enough delay
     * for SF to present the buffers. */
    @Test
    fun testCanPresentBuffers() {
        val numFrames = 15L
        val trace = withTrace { activity ->
            assertEquals(0, activity.mSurfaceProxy.NativeWindowSetSharedBufferMode(true))
            for (i in 1..numFrames) {
                assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
                assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(i, 5000 /* ms */))
            }
        }

        assertThat(trace).hasFrameSequence("SurfaceView", 1..numFrames)
    }

    /** Submit buffers as fast as possible testing that we are not blocked when dequeuing the buffer
     * by setting the dequeue timeout to 1ms and checking that we present the newest buffer. */
    @Test
    fun testFastQueueBuffers() {
        val numFrames = 15L
        val trace = withTrace { activity ->
            assertEquals(0, activity.mSurfaceProxy.NativeWindowSetSharedBufferMode(true))
            for (i in 1..numFrames) {
                assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            }
            assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(numFrames,
                    5000 /* ms */))
        }

        assertThat(trace).hasFrameSequence("SurfaceView", numFrames..numFrames)
    }

    /** Keep overwriting the buffer without queuing buffers and check that we present the latest
     * buffer content. */
    @Test
    fun testAutoRefresh() {
        var svBounds = Rect()
        runOnUiThread {
            assertEquals(0, it.mSurfaceProxy.NativeWindowSetSharedBufferMode(true))
            assertEquals(0, it.mSurfaceProxy.NativeWindowSetAutoRefresh(true))
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
            it.mSurfaceProxy.SurfaceQueueBuffer(0, false /* freeSlot */)
            assertEquals(0, it.mSurfaceProxy.waitUntilBufferDisplayed(1, 5000 /* ms */))

            svBounds = Rect(0, 0, it.mSurfaceView!!.width, it.mSurfaceView!!.height)
            val position = Rect()
            it.mSurfaceView!!.getBoundsOnScreen(position)
            svBounds.offsetTo(position.left, position.top)
        }

        runOnUiThread {
            it.mSurfaceProxy.drawBuffer(0, Color.RED)
            checkPixels(svBounds, Color.RED)
            it.mSurfaceProxy.drawBuffer(0, Color.GREEN)
            checkPixels(svBounds, Color.GREEN)
            it.mSurfaceProxy.drawBuffer(0, Color.BLUE)
            checkPixels(svBounds, Color.BLUE)
        }
    }
}