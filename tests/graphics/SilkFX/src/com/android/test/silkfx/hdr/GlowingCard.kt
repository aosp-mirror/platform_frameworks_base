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
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import com.android.test.silkfx.common.BaseDrawingView

class GlowingCard : BaseDrawingView {

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    val radius: Float
    var COLOR_MAXIMIZER = 1f

    init {
        radius = 10.dp()
    }

    fun setGlowIntensity(multiplier: Float) {
        COLOR_MAXIMIZER = multiplier
        invalidate()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint()
        paint.isAntiAlias = true
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val glowColor = Color.pack(.5f * COLOR_MAXIMIZER, .4f * COLOR_MAXIMIZER,
                .75f * COLOR_MAXIMIZER, 1f, scRGB)

        if (isPressed) {
            paint.setColor(Color.pack(2f, 2f, 2f, 1f, scRGB))
            paint.strokeWidth = 4.dp()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(rect.left, rect.bottom, rect.right, rect.top,
                glowColor,
                Color.pack(0f, 0f, 0f, 0f, scRGB),
                Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, radius, radius, paint)
        }

        rect.inset(3.dp(), 3.dp())

        paint.setColor(Color.pack(.14f, .14f, .14f, .8f, scRGB))
        paint.style = Paint.Style.FILL
        paint.shader = null
        canvas.drawRoundRect(rect, radius, radius, paint)

        rect.inset(5.dp(), 5.dp())
        paint.textSize = 14.dp()
        paint.isFakeBoldText = true

        paint.color = Color.WHITE
        canvas.drawText("glow = scRGB{${Color.red(glowColor)}, ${Color.green(glowColor)}, " +
                "${Color.blue(glowColor)}}", rect.left, rect.centerY(), paint)
        canvas.drawText("(press to activate)", rect.left, rect.bottom, paint)
    }
}