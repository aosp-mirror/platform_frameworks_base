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

import android.tools.flicker.subject.layers.LayersTraceSubject
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BufferPresentationTests(useBlastAdapter: Boolean) : SurfaceTracingTestBase(useBlastAdapter) {
    /** Submit buffers as fast as possible and make sure they are presented on display */
    @Test
    fun testQueueBuffers() {
        val numFrames = 100L
        val trace = withTrace { activity ->
            for (i in 1..numFrames) {
                activity.mSurfaceProxy.ANativeWindowLock()
                activity.mSurfaceProxy.ANativeWindowUnlockAndPost()
            }
            assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(numFrames,
                    1000 /* ms */))
        }

        LayersTraceSubject(trace).hasFrameSequence("SurfaceView", 1..numFrames)
    }

    @Test
    fun testSetBufferScalingMode_outOfOrderQueueBuffer() {
        val trace = withTrace { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))

            activity.mSurfaceProxy.SurfaceQueueBuffer(1)
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(2, 5000 /* ms */))
        }

        LayersTraceSubject(trace).hasFrameSequence("SurfaceView", 1..2L)
    }

    @Test
    fun testSetBufferScalingMode_multipleDequeueBuffer() {
        val numFrames = 20L
        val trace = withTrace { activity ->
            for (count in 1..(numFrames / 2)) {
                assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
                assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))

                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
                activity.mSurfaceProxy.SurfaceQueueBuffer(1)
            }
            assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(numFrames,
                    5000 /* ms */))
        }

        LayersTraceSubject(trace).hasFrameSequence("SurfaceView", 1..numFrames)
    }

    @Test
    fun testSetBufferCount_queueMaxBufferCountMinusOne() {
        val numBufferCount = 8
        val numFrames = numBufferCount * 5L
        val trace = withTrace { activity ->
            assertEquals(0, activity.mSurfaceProxy.NativeWindowSetBufferCount(numBufferCount + 1))
            for (i in 1..numFrames / numBufferCount) {
                for (bufferSlot in 0..numBufferCount - 1) {
                    assertEquals(0,
                            activity.mSurfaceProxy.SurfaceDequeueBuffer(bufferSlot, 1000 /* ms */))
                }

                for (bufferSlot in 0..numBufferCount - 1) {
                    activity.mSurfaceProxy.SurfaceQueueBuffer(bufferSlot)
                }
            }
            assertEquals(0, activity.mSurfaceProxy.waitUntilBufferDisplayed(numFrames,
                    5000 /* ms */))
        }

        LayersTraceSubject(trace).hasFrameSequence("SurfaceView", 1..numFrames)
    }

    @Test
    // Leave IGBP in sync mode, try to dequeue and queue as fast as possible. Check that we
    // occasionally get timeout errors.
    fun testSyncMode_dequeueWithoutBlockingFails() {
        val numFrames = 1000L
        runOnUiThread { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetDequeueTimeout(3L))
            var failures = false
            for (i in 1..numFrames) {
                if (activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 0 /* ms */) != 0) {
                    failures = true
                    break
                }
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            }
            assertTrue(failures)
        }
    }

    @Test
    // Set IGBP to be in async mode, try to dequeue and queue as fast as possible. Client should be
    // able to dequeue and queue buffers without being blocked.
    fun testAsyncMode_dequeueWithoutBlocking() {
        val numFrames = 1000L
        runOnUiThread { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetDequeueTimeout(3L))
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetAsyncMode(async = true))
            for (i in 1..numFrames) {
                assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 0 /* ms */))
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            }
        }
    }

    @Test
    // Disable triple buffering in the system and leave IGBP in sync mode. Check that we
    // occasionally get timeout errors.
    fun testSyncModeWithDisabledTripleBuffering_dequeueWithoutBlockingFails() {
        val numFrames = 1000L
        runOnUiThread { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetMaxDequeuedBufferCount(1))
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetDequeueTimeout(3L))
            var failures = false
            for (i in 1..numFrames) {
                if (activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 0 /* ms */) != 0) {
                    failures = true
                    break
                }
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            }
            assertTrue(failures)
        }
    }

    @Test
    // Disable triple buffering in the system and set IGBP to be in async mode. Try to dequeue and
    // queue as fast as possible. Without triple buffering, the client does not have an extra buffer
    // to dequeue and will not be able to dequeue and queue buffers without being blocked.
    fun testAsyncModeWithDisabledTripleBuffering_dequeueWithoutBlockingFails() {
        val numFrames = 1000L
        runOnUiThread { activity ->
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetMaxDequeuedBufferCount(1))
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetDequeueTimeout(3L))
            assertEquals(0, activity.mSurfaceProxy.SurfaceSetAsyncMode(async = true))
            var failures = false
            for (i in 1..numFrames) {
                if (activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 0 /* ms */) != 0) {
                    failures = true
                    break
                }
                activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            }
            assertTrue(failures)
        }
    }
}
