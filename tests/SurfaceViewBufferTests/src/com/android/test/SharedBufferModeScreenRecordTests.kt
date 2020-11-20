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
import android.os.SystemClock
import android.view.cts.surfacevalidator.PixelColor
import junit.framework.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SharedBufferModeScreenRecordTests(useBlastAdapter: Boolean) :
        ScreenRecordTestBase(useBlastAdapter) {

    /** When auto refresh is set, surface flinger will wake up and refresh the display presenting
     * the latest content in the buffer.
     */
    @Test
    fun testAutoRefresh() {
        var svBounds = Rect()
        runOnUiThread {
            assertEquals(0, it.mSurfaceProxy.NativeWindowSetSharedBufferMode(true))
            assertEquals(0, it.mSurfaceProxy.NativeWindowSetAutoRefresh(true))
            assertEquals(0, it.mSurfaceProxy.SurfaceDequeueBuffer(0, 1 /* ms */))
            it.mSurfaceProxy.SurfaceQueueBuffer(0, false /* freeSlot */)
            assertEquals(0,
                    it.mSurfaceProxy.waitUntilBufferDisplayed(1, 5000 /* ms */))

            svBounds = Rect(0, 0, it.mSurfaceView!!.width, it.mSurfaceView!!.height)
            val position = Rect()
            it.mSurfaceView!!.getBoundsOnScreen(position)
            svBounds.offsetTo(position.left, position.top)

            // wait for buffers from other layers to be latched and transactions to be processed before
            // updating the buffer
            SystemClock.sleep(4000)
        }

        val result = withScreenRecording(svBounds, PixelColor.RED) {
            it.mSurfaceProxy.drawBuffer(0, Color.RED)
        }
        val failRatio = 1.0f * result.failFrames / (result.failFrames + result.passFrames)

        assertTrue("Error: " + result.failFrames +
                " incorrect frames observed (out of " + (result.failFrames + result.passFrames) +
                " frames)", failRatio < 0.05)
        assertTrue("Error: Did not receive sufficient frame updates expected: >1000 actual:" +
                result.passFrames, result.passFrames > 1000)
    }
}