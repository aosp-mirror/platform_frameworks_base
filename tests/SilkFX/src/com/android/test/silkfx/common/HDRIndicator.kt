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

package com.android.test.silkfx.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class HDRIndicator(context: Context) : View(context) {
    constructor(context: Context, attrs: AttributeSet?) : this(context)

    val scRGB = ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val paint = Paint()
        paint.isAntiAlias = true
        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        paint.textSize = height.toFloat()

        canvas.drawColor(Color.pack(1f, 1f, 1f, 1f, scRGB))

        paint.setColor(Color.pack(1.1f, 1.1f, 1.1f, 1f, scRGB))
        canvas.drawText("H", rect.left, rect.bottom, paint)
        paint.setColor(Color.pack(1.2f, 1.2f, 1.2f, 1f, scRGB))
        canvas.drawText("D", rect.left + height.toFloat(), rect.bottom, paint)
        paint.setColor(Color.pack(1.3f, 1.3f, 1.3f, 1f, scRGB))
        canvas.drawText("R", rect.left + height.toFloat() * 2, rect.bottom, paint)
    }
}