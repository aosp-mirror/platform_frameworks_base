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

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.util.AttributeSet
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowInsets
import android.view.WindowInsetsController

class FrontBufferedLayer : SurfaceView, SurfaceHolder.Callback {
    var mRenderer: PenStylusActivity.SingleBufferedCanvas? = null

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    init {
        holder.addCallback(this)
        setZOrderOnTop(true)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        nDestroy(mNativePtr)
        mNativePtr = nCreate(holder.surface)
        mRenderer = PenStylusActivity.SingleBufferedCanvas(width, height)
        clearOverlay()

        if ((false)) {
            val canvas = holder.lockCanvas()
            canvas.drawColor(Color.LTGRAY)
            holder.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        mRenderer = null
        nDestroy(mNativePtr)
        mNativePtr = 0
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
        this.windowInsetsController?.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT)
        this.windowInsetsController?.hide(WindowInsets.Type.navigationBars())
        this.windowInsetsController?.hide(WindowInsets.Type.statusBars())
    }

    private fun clearOverlay() {
        mRenderer?.let {
            it.update(null) {
                drawColor(Color.WHITE, BlendMode.SRC)
            }
            nUpdateBuffer(mNativePtr, it.mHardwareBuffer)
        }
    }

    private var prevX: Float = 0f
    private var prevY: Float = 0f
    private val paint = Paint().also {
        it.color = Color.BLACK
        it.strokeWidth = 10f
        it.isAntiAlias = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_STYLUS)) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                clearOverlay()
            }
            return true
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN ||
                action == MotionEvent.ACTION_MOVE) {
            mRenderer?.let {
                val left = minOf(prevX, event.x).toInt() - 10
                val top = minOf(prevY, event.y).toInt() - 10
                val right = maxOf(prevX, event.x).toInt() + 10
                val bottom = maxOf(prevY, event.y).toInt() + 10
                it.update(Rect(left, top, right, bottom)) {
                    if (action == MotionEvent.ACTION_MOVE) {
                        drawLine(prevX, prevY, event.x, event.y, paint)
                    }
                    drawCircle(event.x, event.y, 5f, paint)
                }
                nUpdateBuffer(mNativePtr, it.mHardwareBuffer)
            }
            prevX = event.x
            prevY = event.y
        }
        return true
    }

    private var mNativePtr: Long = 0

    private external fun nCreate(surface: Surface): Long
    private external fun nDestroy(ptr: Long)
    private external fun nUpdateBuffer(ptr: Long, buffer: HardwareBuffer)

    companion object {
        init {
            System.loadLibrary("hwaccelerationtest_jni")
        }
    }
}