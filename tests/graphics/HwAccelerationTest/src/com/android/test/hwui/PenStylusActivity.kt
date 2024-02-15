/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.test.hwui

import android.app.Activity
import android.os.Bundle
import android.hardware.HardwareBuffer
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.view.Surface
import java.lang.IllegalArgumentException

const val USAGE_HWC = 0x800L

class PenStylusActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(FrontBufferedLayer(this))
    }

    class SingleBufferedCanvas : AutoCloseable {
        val mHardwareBuffer: HardwareBuffer
        private var mCanvas: Canvas?
        private val mImageReader: ImageReader
        private val mSurface: Surface

        constructor(width: Int, height: Int) {
            mImageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_CPU_READ_RARELY or HardwareBuffer.USAGE_CPU_WRITE_RARELY or
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or USAGE_HWC)

            mSurface = mImageReader.surface
            mSurface.unlockCanvasAndPost(mSurface.lockCanvas(null))
            val image = mImageReader.acquireNextImage()
            mHardwareBuffer = image.hardwareBuffer!!
            image.close()
            mCanvas = mSurface.lockCanvas(null)
        }

        fun lockCanvas(rect: Rect?): Canvas {
            if (mCanvas != null) {
                unlockCanvas(mCanvas!!)
            }
            mCanvas = mSurface.lockCanvas(rect)
            return mCanvas!!
        }

        fun unlockCanvas(canvas: Canvas) {
            if (this.mCanvas !== canvas) throw IllegalArgumentException()
            mSurface.unlockCanvasAndPost(canvas)
            this.mCanvas = null
            mImageReader.acquireNextImage().close()
        }

        inline fun update(area: Rect?, func: Canvas.() -> Unit) {
            val canvas = lockCanvas(area)
            func(canvas)
            unlockCanvas(canvas)
        }

        override fun close() {
            mHardwareBuffer.close()
            mSurface.unlockCanvasAndPost(mCanvas)
            mImageReader.close()
        }
    }
}