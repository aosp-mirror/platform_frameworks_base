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

import com.android.server.wm.flicker.traces.layers.LayersTraceSubject.Companion.assertThat
import junit.framework.Assert.assertEquals
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

        assertThat(trace).hasFrameSequence("SurfaceView", 1..numFrames)
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

        assertThat(trace).hasFrameSequence("SurfaceView", 1..2L)
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

        assertThat(trace).hasFrameSequence("SurfaceView", 1..numFrames)
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

        assertThat(trace).hasFrameSequence("SurfaceView", 1..numFrames)
    }
}