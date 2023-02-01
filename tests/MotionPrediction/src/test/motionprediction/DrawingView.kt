/*
 * Copyright (C) 2023 The Android Open Source Project
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

package test.motionprediction

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionPredictor
import android.view.View

import java.util.Vector

private fun drawLine(canvas: Canvas, from: MotionEvent, to: MotionEvent, paint: Paint) {
    canvas.apply {
        val x0 = from.getX()
        val y0 = from.getY()
        val x1 = to.getX()
        val y1 = to.getY()
        // TODO: handle historical data
        drawLine(x0, y0, x1, y1, paint)
    }
}

/**
 * Draw the current stroke and predicted values
 */
class DrawingView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private val TAG = "DrawingView"

    val events: MutableMap<Int, Vector<MotionEvent>> = mutableMapOf<Int, Vector<MotionEvent>>()

    var isPredictionAvailable = false
    private val predictor = MotionPredictor(getContext())

    private var predictionPaint = Paint()
    private var realPaint = Paint()

    init {
        setBackgroundColor(Color.WHITE)
        predictionPaint.color = Color.BLACK
        predictionPaint.setStrokeWidth(5f)
        realPaint.color = Color.RED
        realPaint.setStrokeWidth(5f)
    }

    private fun addEvent(event: MotionEvent) {
        if (event.getActionMasked() == ACTION_DOWN) {
            events.remove(event.deviceId)
        }
        var vec = events.getOrPut(event.deviceId) { Vector<MotionEvent>() }
        vec.add(MotionEvent.obtain(event))
        predictor.record(event)
        invalidate()
    }

    public override fun onTouchEvent(event: MotionEvent): Boolean {
        isPredictionAvailable = predictor.isPredictionAvailable(event.getDeviceId(),
                                                                event.getSource())
        addEvent(event)
        return true
    }

    public override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isPredictionAvailable) {
            canvas.apply {
                drawRect(0f, 0f, 200f, 200f, realPaint)
            }
        }

        var eventTime = 0L

        // Draw real events
        for ((_, vec) in events ) {
            for (i in 1 until vec.size) {
                drawLine(canvas, vec[i - 1], vec[i], realPaint)
            }
            eventTime = vec.lastElement().eventTime
        }

        // Draw predictions. Convert to nanos and hardcode to +20ms into the future
        val predictionList = predictor.predict(eventTime * 1000000 + 20000000)
        for (prediction in predictionList) {
            val realEvents = events.get(prediction.deviceId)!!
            drawLine(canvas, realEvents[realEvents.size - 1], prediction, predictionPaint)
        }
    }
}
