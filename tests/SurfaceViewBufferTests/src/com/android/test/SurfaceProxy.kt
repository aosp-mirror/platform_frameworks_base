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

import android.annotation.ColorInt
import android.graphics.Color
import android.graphics.Point
import com.android.test.SurfaceViewBufferTestBase.Companion.ScalingMode
import com.android.test.SurfaceViewBufferTestBase.Companion.Transform

class SurfaceProxy {
    init {
        System.loadLibrary("surface_jni")
    }

    external fun setSurface(surface: Any)
    external fun waitUntilBufferDisplayed(frameNumber: Long, timeoutMs: Int): Int
    external fun draw()
    fun drawBuffer(slot: Int, @ColorInt c: Int) {
        drawBuffer(slot, intArrayOf(Color.red(c), Color.green(c), Color.blue(c), Color.alpha(c)))
    }
    external fun drawBuffer(slot: Int, color: IntArray)

    // android/native_window.h functions
    external fun ANativeWindowLock()
    external fun ANativeWindowUnlockAndPost()
    fun ANativeWindowSetBuffersGeometry(surface: Any, size: Point, format: Int) {
        ANativeWindowSetBuffersGeometry(surface, size.x, size.y, format)
    }
    external fun ANativeWindowSetBuffersGeometry(surface: Any, width: Int, height: Int, format: Int)
    fun ANativeWindowSetBuffersTransform(transform: Transform) {
        ANativeWindowSetBuffersTransform(transform.value)
    }
    external fun ANativeWindowSetBuffersTransform(transform: Int)

    // gui/Surface.h functions
    fun SurfaceSetScalingMode(scalingMode: ScalingMode) {
        SurfaceSetScalingMode(scalingMode.ordinal)
    }
    external fun SurfaceSetScalingMode(scalingMode: Int)
    external fun SurfaceDequeueBuffer(slot: Int, timeoutMs: Int): Int
    external fun SurfaceCancelBuffer(slot: Int)
    external fun SurfaceQueueBuffer(slot: Int, freeSlot: Boolean = true): Int
    external fun SurfaceSetAsyncMode(async: Boolean): Int
    external fun SurfaceSetDequeueTimeout(timeout: Long): Int
    external fun SurfaceQuery(what: Int): Int
    external fun SurfaceSetMaxDequeuedBufferCount(maxDequeuedBuffers: Int): Int

    // system/native_window.h functions
    external fun NativeWindowSetBufferCount(count: Int): Int
    external fun NativeWindowSetSharedBufferMode(shared: Boolean): Int
    external fun NativeWindowSetAutoRefresh(autoRefresh: Boolean): Int
}
