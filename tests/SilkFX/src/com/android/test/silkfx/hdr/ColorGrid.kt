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

package com.android.test.silkfx.hdr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import com.android.test.silkfx.common.BaseDrawingView

class ColorGrid(context: Context, attrs: AttributeSet?) : BaseDrawingView(context, attrs) {

    init {
        isClickable = true
        setOnClickListener {
            invalidate()
        }
    }

    fun toMaxColor(color: Int, colorspace: ColorSpace): Long {
        val red = (Color.red(color) / 255f) * colorspace.getMaxValue(0)
        val green = (Color.green(color) / 255f) * colorspace.getMaxValue(1)
        val blue = (Color.blue(color) / 255f) * colorspace.getMaxValue(2)
        val alpha = Color.alpha(color) / 255f
        return Color.pack(red, green, blue, alpha, colorspace)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paint = Paint()
        paint.isDither = true
        paint.isAntiAlias = true
        paint.textSize = 18.dp()
        paint.textAlign = Paint.Align.LEFT

        val labels = arrayOf("sRGB", "Display P3", "BT2020_PQ", "scRGB(max)")
        val colorSpaces = arrayOf(sRGB, displayP3, bt2020_pq, scRGB)

        val colWidth = width / colorSpaces.size.toFloat()
        val rowHeight = minOf((height - 20.dp()) / 4f, colWidth)

        val dest = Rect(0, 0, rowHeight.toInt(), colWidth.toInt())

        for (colIndex in labels.indices) {
            canvas.save()
            canvas.translate(colIndex * colWidth, 20.dp())

            paint.color = Color.WHITE
            canvas.drawText(labels[colIndex], 0f, 1f, paint)

            arrayOf(Color.WHITE, Color.RED, Color.BLUE, Color.GREEN).forEach {
                paint.setColor(toMaxColor(it, colorSpaces[colIndex]))
                canvas.drawRect(dest, paint)
                canvas.translate(0f, rowHeight)
            }
            canvas.restore()
        }
    }
}