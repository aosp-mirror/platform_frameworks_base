/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.biometrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.FrameLayout

private const val TAG = "UdfpsOverlayView"

class UdfpsOverlayView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {

    private val sensorRect = RectF()
    var overlayParams = UdfpsOverlayParams()
    private var mUdfpsDisplayMode: UdfpsDisplayMode? = null

    var overlayPaint = Paint()
    var sensorPaint = Paint()
    val centerPaint = Paint()

    /** True after the call to [configureDisplay] and before the call to [unconfigureDisplay]. */
    var isDisplayConfigured: Boolean = false
        private set

    init {
        this.setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        overlayPaint.color = Color.argb(120, 255, 0, 0)
        overlayPaint.style = Paint.Style.FILL

        sensorPaint.color = Color.argb(150, 134, 204, 255)
        sensorPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawRect(overlayParams.overlayBounds, overlayPaint)
        canvas.drawRect(overlayParams.sensorBounds, sensorPaint)
        canvas.drawCircle(
            overlayParams.sensorBounds.exactCenterX(),
            overlayParams.sensorBounds.exactCenterY(),
            overlayParams.sensorBounds.width().toFloat() / 2,
            centerPaint
        )
    }

    fun setUdfpsDisplayMode(udfpsDisplayMode: UdfpsDisplayMode?) {
        mUdfpsDisplayMode = udfpsDisplayMode
    }

    fun configureDisplay(onDisplayConfigured: Runnable) {
        isDisplayConfigured = true
        mUdfpsDisplayMode?.enable(onDisplayConfigured)
    }

    fun unconfigureDisplay() {
        isDisplayConfigured = false
        mUdfpsDisplayMode?.disable(null /* onDisabled */)
    }
}
