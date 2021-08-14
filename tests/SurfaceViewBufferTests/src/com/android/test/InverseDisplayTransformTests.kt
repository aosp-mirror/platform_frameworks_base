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
import com.android.test.SurfaceViewBufferTestBase.Companion.Transform
import junit.framework.Assert.assertEquals
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class InverseDisplayTransformTests(useBlastAdapter: Boolean) :
        SurfaceTracingTestBase(useBlastAdapter) {
    @Before
    override fun setup() {
        scenarioRule.getScenario().onActivity {
            it.rotate90()
        }
        instrumentation.waitForIdleSync()
        super.setup()
    }

    @Test
    fun testSetBufferScalingMode_freeze_withInvDisplayTransform() {
        assumeFalse("Blast does not support buffer rejection with Inv display " +
                "transform since the only user for this hidden api is camera which does not use" +
                "fixed scaling mode.", useBlastAdapter)

        val rotatedBufferSize = Point(defaultBufferSize.y, defaultBufferSize.x)
        val trace = withTrace { activity ->
            // Inverse display transforms are sticky AND they are only consumed by the sf after
            // a valid buffer has been acquired.
            activity.mSurfaceProxy.ANativeWindowSetBuffersTransform(Transform.INVERSE_DISPLAY.value)
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)

            assertEquals(activity.mSurfaceProxy.waitUntilBufferDisplayed(1, 500 /* ms */), 0)
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!,
                    rotatedBufferSize, R8G8B8A8_UNORM)
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(0, 1000 /* ms */))
            assertEquals(0, activity.mSurfaceProxy.SurfaceDequeueBuffer(1, 1000 /* ms */))
            // Change buffer size and set scaling mode to freeze
            activity.mSurfaceProxy.ANativeWindowSetBuffersGeometry(activity.surface!!, Point(0, 0),
                    R8G8B8A8_UNORM)

            // first dequeued buffer does not have the new size so it should be rejected.
            activity.mSurfaceProxy.ANativeWindowSetBuffersTransform(Transform.ROT_90.value)
            activity.mSurfaceProxy.SurfaceQueueBuffer(0)
            activity.mSurfaceProxy.ANativeWindowSetBuffersTransform(0)
            activity.mSurfaceProxy.SurfaceQueueBuffer(1)
            assertEquals(activity.mSurfaceProxy.waitUntilBufferDisplayed(3, 500 /* ms */), 0)
        }

        // verify buffer size is reset to default buffer size
        assertThat(trace).layer("SurfaceView", 1).hasBufferSize(defaultBufferSize)
        assertThat(trace).layer("SurfaceView", 2).doesNotExist()
        assertThat(trace).layer("SurfaceView", 3).hasBufferSize(rotatedBufferSize)
    }
}