/*
 * Copyright 2023 The Android Open Source Project
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

package test.multideviceinput

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.InputDevice.SOURCE_STYLUS
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_EXIT
import android.view.MotionEvent.ACTION_UP
import android.view.ScaleGestureDetector
import android.view.View

import java.util.Vector

private fun drawLine(canvas: Canvas, from: MotionEvent, to: MotionEvent, paint: Paint) {
    // Correct implementation here would require us to build a set of pointers and then iterate
    // through them. Instead, we are taking a few shortcuts and ignore some of the events, which
    // causes occasional gaps in the drawings.
    if (from.pointerCount != to.pointerCount) {
        return
    }
    // Now, 'from' is guaranteed to have as many pointers as the 'to' event. It doesn't
    // necessarily mean they are the same pointers, though.
    for (p in 0..<from.pointerCount) {
        val x0 = from.getX(p)
        val y0 = from.getY(p)
        if (to.getPointerId(p) == from.getPointerId(p)) {
            // This only works when the i-th pointer in "to" is the same pointer
            // as the i-th pointer in "from"`. It's not guaranteed by the input APIs,
            // but it works in practice.
            val x1 = to.getX(p)
            val y1 = to.getY(p)
            // Ignoring historical data here for simplicity
            canvas.drawLine(x0, y0, x1, y1, paint)
        }
    }
}

private fun drawCircle(canvas: Canvas, event: MotionEvent, paint: Paint, radius: Float) {
    val x = event.getX()
    val y = event.getY()
    canvas.drawCircle(x, y, radius, paint)
}

/**
 * Draw the current stroke
 */
class DrawingView : View {
    private val TAG = "DrawingView"

    private var myState: SharedScaledPointerSize? = null
    private var otherState: SharedScaledPointerSize? = null

    constructor(
            context: Context,
            myState: SharedScaledPointerSize,
            otherState: SharedScaledPointerSize
            ) : super(context) {
        this.myState = myState
        this.otherState = otherState
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    val touchEvents = mutableMapOf<Int, Vector<Pair<MotionEvent, Paint>>>()
    val hoverEvents = mutableMapOf<Int, MotionEvent>()

    val scaleGestureListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

        override fun onScaleBegin(scaleGestureDetector: ScaleGestureDetector): Boolean {
            return true
        }

        override fun onScale(scaleGestureDetector: ScaleGestureDetector): Boolean {
            val scaleFactor = scaleGestureDetector.scaleFactor
            when (otherState?.state) {
                PointerState.DOWN -> {
                    otherState?.lineSize = (otherState?.lineSize ?: 5f) * scaleFactor
                }
                PointerState.HOVER -> {
                    otherState?.circleSize = (otherState?.circleSize ?: 20f) * scaleFactor
                }
                else -> {}
            }
            return true
        }
    }
    private val scaleGestureDetector = ScaleGestureDetector(context, scaleGestureListener, null)

    private var touchPaint = Paint()
    private var stylusPaint = Paint()

    private fun init() {
        touchPaint.color = Color.RED
        touchPaint.setStrokeWidth(5f)
        stylusPaint.color = Color.YELLOW
        stylusPaint.setStrokeWidth(5f)

        setOnHoverListener { _, event -> processHoverEvent(event); true }
    }

    private fun processTouchEvent(event: MotionEvent) {
        scaleGestureDetector.onTouchEvent(event)
        if (event.actionMasked == ACTION_DOWN) {
            touchEvents.remove(event.deviceId)
            myState?.state = PointerState.DOWN
        } else if (event.actionMasked == ACTION_UP) {
            myState?.state = PointerState.NONE
        }
        var vec = touchEvents.getOrPut(event.deviceId) { Vector<Pair<MotionEvent, Paint>>() }

        val paint = if (event.isFromSource(SOURCE_STYLUS)) {
            val size = myState?.lineSize ?: 5f
            stylusPaint.setStrokeWidth(size)
            Paint(stylusPaint)
        } else {
            val size = myState?.lineSize ?: 5f
            touchPaint.setStrokeWidth(size)
            Paint(touchPaint)
        }
        vec.add(Pair(MotionEvent.obtain(event), paint))
        invalidate()
    }

    private fun processHoverEvent(event: MotionEvent) {
        hoverEvents.remove(event.deviceId)
        if (event.getActionMasked() != ACTION_HOVER_EXIT) {
            hoverEvents.put(event.deviceId, MotionEvent.obtain(event))
            myState?.state = PointerState.HOVER
        } else {
            myState?.state = PointerState.NONE
        }
        invalidate()
    }

    public override fun onTouchEvent(event: MotionEvent): Boolean {
        processTouchEvent(event)
        return true
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw touch and stylus MotionEvents
        for ((_, vec) in touchEvents ) {
            for (i in 1 until vec.size) {
                drawLine(canvas, vec[i - 1].first, vec[i].first, vec[i].second)
            }
        }
        // Draw hovers
        for ((_, event) in hoverEvents ) {
            if (event.isFromSource(SOURCE_STYLUS)) {
                val size = myState?.circleSize ?: 20f
                drawCircle(canvas, event, stylusPaint, size)
            } else {
                val size = myState?.circleSize ?: 20f
                drawCircle(canvas, event, touchPaint, size)
            }
        }
    }
}
