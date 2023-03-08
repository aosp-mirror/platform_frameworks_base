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
import android.graphics.Point
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.android.settingslib.udfps.UdfpsOverlayParams

private const val TAG = "UdfpsOverlayView"
private const val POINT_SIZE = 10f

class UdfpsOverlayView(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    var overlayParams = UdfpsOverlayParams()
    private var mUdfpsDisplayMode: UdfpsDisplayMode? = null

    var debugOverlay = false

    var overlayPaint = Paint()
    var sensorPaint = Paint()
    var touchPaint = Paint()
    var pointPaint = Paint()
    val centerPaint = Paint()

    var oval = RectF()

    /** True after the call to [configureDisplay] and before the call to [unconfigureDisplay]. */
    var isDisplayConfigured: Boolean = false
        private set

    var touchX: Float = 0f
    var touchY: Float = 0f
    var touchMinor: Float = 0f
    var touchMajor: Float = 0f
    var touchOrientation: Double = 0.0

    var sensorPoints: Array<Point>? = null

    init {
        this.setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        overlayPaint.color = Color.argb(100, 255, 0, 0)
        overlayPaint.style = Paint.Style.FILL

        touchPaint.color = Color.argb(200, 255, 255, 255)
        touchPaint.style = Paint.Style.FILL

        sensorPaint.color = Color.argb(150, 134, 204, 255)
        sensorPaint.style = Paint.Style.FILL

        pointPaint.color = Color.WHITE
        pointPaint.style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (debugOverlay) {
            // Draw overlay and sensor bounds
            canvas.drawRect(overlayParams.overlayBounds, overlayPaint)
            canvas.drawRect(overlayParams.sensorBounds, sensorPaint)
        }

        // Draw sensor circle
        canvas.drawCircle(
            overlayParams.sensorBounds.exactCenterX(),
            overlayParams.sensorBounds.exactCenterY(),
            overlayParams.sensorBounds.width().toFloat() / 2,
            centerPaint
        )

        if (debugOverlay) {
            // Draw Points
            sensorPoints?.forEach {
                canvas.drawCircle(it.x.toFloat(), it.y.toFloat(), POINT_SIZE, pointPaint)
            }

            // Draw touch oval
            canvas.save()
            canvas.rotate(Math.toDegrees(touchOrientation).toFloat(), touchX, touchY)

            oval.setEmpty()
            oval.set(
                touchX - touchMinor / 2,
                touchY + touchMajor / 2,
                touchX + touchMinor / 2,
                touchY - touchMajor / 2
            )

            canvas.drawOval(oval, touchPaint)

            // Draw center point
            canvas.drawCircle(touchX, touchY, POINT_SIZE, centerPaint)
            canvas.restore()
        }
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

    fun processMotionEvent(event: MotionEvent) {
        touchX = event.rawX
        touchY = event.rawY
        touchMinor = event.touchMinor
        touchMajor = event.touchMajor
        touchOrientation = event.orientation.toDouble()
    }
}
