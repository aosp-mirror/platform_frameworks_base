/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.egg.paint

import android.content.Context
import android.graphics.*
import android.provider.Settings
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import java.util.concurrent.TimeUnit
import android.provider.Settings.System

import org.json.JSONObject

fun hypot(x: Float, y: Float): Float {
    return Math.hypot(x.toDouble(), y.toDouble()).toFloat()
}

fun invlerp(x: Float, a: Float, b: Float): Float {
    return if (b > a) {
        (x - a) / (b - a)
    } else 1.0f
}

public class Painting : View, SpotFilter.Plotter {
    companion object {
        val FADE_MINS = TimeUnit.MINUTES.toMillis(3) // about how long a drawing should last
        val ZEN_RATE = TimeUnit.SECONDS.toMillis(2)  // how often to apply the fade
        val ZEN_FADE = Math.max(1f, ZEN_RATE / FADE_MINS * 255f)

        val FADE_TO_WHITE_CF = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, ZEN_FADE,
                0f, 1f, 0f, 0f, ZEN_FADE,
                0f, 0f, 1f, 0f, ZEN_FADE,
                0f, 0f, 0f, 1f, 0f
        )))

        val FADE_TO_BLACK_CF = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, -ZEN_FADE,
                0f, 1f, 0f, 0f, -ZEN_FADE,
                0f, 0f, 1f, 0f, -ZEN_FADE,
                0f, 0f, 0f, 1f, 0f
        )))

        val INVERT_CF = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
        )))

        val TOUCH_STATS = "touch.stats" // Settings.System key
    }

    var devicePressureMin = 0f; // ideal value
    var devicePressureMax = 1f; // ideal value

    var zenMode = true
        set(value) {
            if (field != value) {
                field = value
                removeCallbacks(fadeRunnable)
                if (value && isAttachedToWindow) {
                    handler.postDelayed(fadeRunnable, ZEN_RATE)
                }
            }
        }

    var bitmap: Bitmap? = null
    var paperColor: Int = 0xFFFFFFFF.toInt()

    private var _paintCanvas: Canvas? = null
    private val _bitmapLock = Object()

    private var _drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var _lastX = 0f
    private var _lastY = 0f
    private var _lastR = 0f
    private var _insets: WindowInsets? = null

    private var _brushWidth = 100f

    private var _filter = SpotFilter(10, 0.5f, 0.9f, this)

    private val fadeRunnable = object : Runnable {
        private val pt = Paint()
        override fun run() {
            val c = _paintCanvas
            if (c != null) {
                pt.colorFilter =
                    if (paperColor.and(0xFF) > 0x80)
                        FADE_TO_WHITE_CF
                    else
                        FADE_TO_BLACK_CF

                synchronized(_bitmapLock) {
                    bitmap?.let {
                        c.drawBitmap(bitmap!!, 0f, 0f, pt)
                    }
                }
                invalidate()
            }
            postDelayed(this, ZEN_RATE)
        }
    }

    constructor(context: Context) : super(context) {
        init()
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init()
    }

    constructor(
        context: Context,
        attrs: AttributeSet,
        defStyle: Int
    ) : super(context, attrs, defStyle) {
        init()
    }

    private fun init() {
        loadDevicePressureData()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        setupBitmaps()

        if (zenMode) {
            handler.postDelayed(fadeRunnable, ZEN_RATE)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        setupBitmaps()
    }

    override fun onDetachedFromWindow() {
        if (zenMode) {
            removeCallbacks(fadeRunnable)
        }
        super.onDetachedFromWindow()
    }

    fun onTrimMemory() {
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        _insets = insets
        if (insets != null && _paintCanvas == null) {
            setupBitmaps()
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun powf(a: Float, b: Float): Float {
        return Math.pow(a.toDouble(), b.toDouble()).toFloat()
    }

    override fun plot(s: MotionEvent.PointerCoords) {
        val c = _paintCanvas
        if (c == null) return
        synchronized(_bitmapLock) {
            var x = _lastX
            var y = _lastY
            var r = _lastR
            val newR = Math.max(1f, powf(adjustPressure(s.pressure), 2f).toFloat() * _brushWidth)

            if (r >= 0) {
                val d = hypot(s.x - x, s.y - y)
                if (d > 1f && (r + newR) > 1f) {
                    val N = (2 * d / Math.min(4f, r + newR)).toInt()

                    val stepX = (s.x - x) / N
                    val stepY = (s.y - y) / N
                    val stepR = (newR - r) / N
                    for (i in 0 until N - 1) { // we will draw the last circle below
                        x += stepX
                        y += stepY
                        r += stepR
                        c.drawCircle(x, y, r, _drawPaint)
                    }
                }
            }

            c.drawCircle(s.x, s.y, newR, _drawPaint)
            _lastX = s.x
            _lastY = s.y
            _lastR = newR
        }
    }

    private fun loadDevicePressureData() {
        try {
            val touchDataJson = Settings.System.getString(context.contentResolver, TOUCH_STATS)
            val touchData = JSONObject(
                    if (touchDataJson != null) touchDataJson else "{}")
            if (touchData.has("min")) devicePressureMin = touchData.getDouble("min").toFloat()
            if (touchData.has("max")) devicePressureMax = touchData.getDouble("max").toFloat()
            if (devicePressureMin < 0) devicePressureMin = 0f
            if (devicePressureMax < devicePressureMin) devicePressureMax = devicePressureMin + 1f
        } catch (e: Exception) {
        }
    }

    private fun adjustPressure(pressure: Float): Float {
        if (pressure > devicePressureMax) devicePressureMax = pressure
        if (pressure < devicePressureMin) devicePressureMin = pressure
        return invlerp(pressure, devicePressureMin, devicePressureMax)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val c = _paintCanvas
        if (event == null || c == null) return super.onTouchEvent(event)

        /*
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.style = Paint.Style.STROKE
        pt.color = 0x800000FF.toInt()
        _paintCanvas?.drawCircle(event.x, event.y, 20f, pt)
        */

        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                _filter.add(event)
                _filter.finish()
                invalidate()
            }

            MotionEvent.ACTION_DOWN -> {
                _lastR = -1f
                _filter.add(event)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                _filter.add(event)

                invalidate()
            }
        }

        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        bitmap?.let {
            canvas.drawBitmap(bitmap!!, 0f, 0f, _drawPaint)
        }
    }

    // public api
    fun clear() {
        bitmap = null
        setupBitmaps()
        invalidate()
    }

    fun sampleAt(x: Float, y: Float): Int {
        val localX = (x - left).toInt()
        val localY = (y - top).toInt()
        return bitmap?.getPixel(localX, localY) ?: Color.BLACK
    }

    fun setPaintColor(color: Int) {
        _drawPaint.color = color
    }

    fun getPaintColor(): Int {
        return _drawPaint.color
    }

    fun setBrushWidth(w: Float) {
        _brushWidth = w
    }

    fun getBrushWidth(): Float {
        return _brushWidth
    }

    private fun setupBitmaps() {
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val oldBits = bitmap
        var bits = oldBits
        if (bits == null || bits.width != w || bits.height != h) {
            bits = Bitmap.createBitmap(
                    w,
                    h,
                    Bitmap.Config.ARGB_8888
            )
        }
        if (bits == null) return

        val c = Canvas(bits)

        if (oldBits != null) {
            if (oldBits.width < oldBits.height != bits.width < bits.height) {
                // orientation change. let's rotate things so they fit better
                val matrix = Matrix()
                if (bits.width > bits.height) {
                    // now landscape
                    matrix.postRotate(-90f)
                    matrix.postTranslate(0f, bits.height.toFloat())
                } else {
                    // now portrait
                    matrix.postRotate(90f)
                    matrix.postTranslate(bits.width.toFloat(), 0f)
                }
                if (bits.width != oldBits.height || bits.height != oldBits.width) {
                    matrix.postScale(
                            bits.width.toFloat() / oldBits.height,
                            bits.height.toFloat() / oldBits.width)
                }
                c.setMatrix(matrix)
            }
            // paint the old artwork atop the new
            c.drawBitmap(oldBits, 0f, 0f, _drawPaint)
            c.setMatrix(Matrix())
        } else {
            c.drawColor(paperColor)
        }

        bitmap = bits
        _paintCanvas = c
    }

    fun invertContents() {
        val invertPaint = Paint()
        invertPaint.colorFilter = INVERT_CF
        synchronized(_bitmapLock) {
            bitmap?.let {
                _paintCanvas?.drawBitmap(bitmap!!, 0f, 0f, invertPaint)
            }
        }
        invalidate()
    }
}
