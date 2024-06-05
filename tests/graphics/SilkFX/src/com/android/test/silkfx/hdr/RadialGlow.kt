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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import com.android.test.silkfx.common.BaseDrawingView
import kotlin.math.min

class RadialGlow(context: Context, attrs: AttributeSet?) : BaseDrawingView(context, attrs) {

    var glowToggle = false

    val glowColor = color(4f, 3.3f, 2.8f)
    val bgColor = color(.15f, .15f, .15f)
    val fgColor = color(.51f, .52f, .50f, .4f)
    var glow: RadialGradient

    init {
        glow = RadialGradient(0f, 0f, 100.dp(), glowColor, bgColor, Shader.TileMode.CLAMP)
        isClickable = true
        setOnClickListener {
            glowToggle = !glowToggle
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glow = RadialGradient(0f, 0f,
            min(w, h).toFloat(), glowColor, bgColor, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = 10.dp()

        val paint = Paint()
        paint.isDither = true
        paint.isAntiAlias = true
        paint.textSize = 18.dp()
        paint.textAlign = Paint.Align.CENTER

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())

        paint.setColor(bgColor)
        canvas.drawRoundRect(rect, radius, radius, paint)

        if (glowToggle) {
            paint.shader = glow
            canvas.save()
            val frac = (drawingTime % 5000) / 5000f
            canvas.translate(rect.width() * frac, rect.height() - (rect.height() * frac))
            canvas.drawPaint(paint)
            canvas.restore()
            paint.shader = null
            invalidate()
        }

        paint.setColor(fgColor)
        val innerRect = RectF(rect)
        innerRect.inset(rect.width() / 4, rect.height() / 4)
        canvas.drawRoundRect(innerRect, radius, radius, paint)

        paint.setColor(color(1f, 1f, 1f))
        canvas.drawText("Tap to toggle animation", rect.centerX(), innerRect.top - 4.dp(), paint)
        canvas.drawText("Outside text", rect.centerX(), rect.bottom - 4.dp(), paint)
        canvas.drawText("Inside text", innerRect.centerX(), innerRect.bottom - 4.dp(), paint)
    }
}