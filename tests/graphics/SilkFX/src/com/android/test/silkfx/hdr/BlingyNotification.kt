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

package com.android.test.silkfx.hdr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import com.android.test.silkfx.common.BaseDrawingView

class BlingyNotification : BaseDrawingView {

    private val image: Bitmap?
    private val bounds = Rect()
    private val paint = Paint()

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        val typed = context.obtainStyledAttributes(attrs, intArrayOf(android.R.attr.src))
        val drawable = typed.getDrawable(0)
        image = if (drawable is BitmapDrawable) {
            drawable.bitmap
        } else {
            null
        }
        typed.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val image = image ?: return super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        // Currently only used in this mode, so that's all we'll bother to support
        if (widthMode == MeasureSpec.EXACTLY && heightMode != MeasureSpec.EXACTLY) {
            val width = MeasureSpec.getSize(widthMeasureSpec)

            var height = image.height * width / image.width
            if (heightMode == MeasureSpec.AT_MOST) {
                height = minOf(MeasureSpec.getSize(heightMeasureSpec), height)
            }
            setMeasuredDimension(width, height)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bounds.set(0, 0, w, h)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
                longArrayOf(
                        color(1f, 1f, 1f, 0f),
                        color(1f, 1f, 1f, .1f),
                        color(2f, 2f, 2f, .3f),
                        color(1f, 1f, 1f, .2f),
                        color(1f, 1f, 1f, 0f)
                        ),
                floatArrayOf(.2f, .4f, .5f, .6f, .8f),
                Shader.TileMode.CLAMP)
        paint.blendMode = BlendMode.PLUS
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val image = image ?: return

        canvas.drawBitmap(image, null, bounds, null)

        canvas.save()
        val frac = ((drawingTime % 2000) / 300f) - 1f
        canvas.translate(width * frac, 0f)
        canvas.rotate(-45f)
        canvas.drawPaint(paint)
        canvas.restore()
        invalidate()
    }
}