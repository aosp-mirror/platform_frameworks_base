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
package com.android.test.silkfx.materials

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import com.android.test.silkfx.R

class GlassView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {

    var noise = BitmapFactory.decodeResource(resources, R.drawable.noise)
    var materialPaint = Paint()
    var scrimPaint = Paint()
    var noisePaint = Paint()
    var blurPaint = Paint()

    val src = Rect()
    val dst = Rect()

    var backgroundBitmap: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    set(value) {
        field = value
        invalidate()
    }

    var noiseOpacity = 0.0f
    set(value) {
        field = value
        noisePaint.alpha = (value * 255).toInt()
        invalidate()
    }

    var materialOpacity = 0.0f
    set(value) {
        field = value
        materialPaint.alpha = (value * 255).toInt()
        invalidate()
    }

    var scrimOpacity = 0.5f
        set(value) {
            field = value
            scrimPaint.alpha = (value * 255).toInt()
            invalidate()
        }

    var zoom = 0.0f
        set(value) {
            field = value
            invalidate()
        }

    var color = Color.BLACK
    set(value) {
        field = value
        var alpha = materialPaint.alpha
        materialPaint.color = color
        materialPaint.alpha = alpha

        alpha = scrimPaint.alpha
        scrimPaint.color = color
        scrimPaint.alpha = alpha
        invalidate()
    }

    var blurRadius = 150f
    set(value) {
        field = value
        blurPaint.shader = BlurShader(value, value, null)
        invalidate()
    }

    init {
        materialPaint.blendMode = BlendMode.SOFT_LIGHT
        noisePaint.blendMode = BlendMode.SOFT_LIGHT
        noisePaint.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scrimPaint.alpha = (scrimOpacity * 255).toInt()
        noisePaint.alpha = (noiseOpacity * 255).toInt()
        materialPaint.alpha = (materialOpacity * 255).toInt()
        blurPaint.shader = BlurShader(blurRadius, blurRadius, null)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View?, outline: Outline?) {
                outline?.setRoundRect(Rect(0, 0, width, height), 100f)
            }
        }
        clipToOutline = true
    }

    override fun onDraw(canvas: Canvas?) {
        src.set(-width/2, -height/2, width/2, height/2)
        src.scale(1.0f + zoom)
        val centerX = left + width / 2
        val centerY = top + height / 2
        src.set(src.left + centerX, src.top + centerY, src.right + centerX, src.bottom + centerY)

        dst.set(0, 0, width, height)
        canvas?.drawBitmap(backgroundBitmap, src, dst, blurPaint)
        canvas?.drawRect(dst, materialPaint)
        canvas?.drawRect(dst, noisePaint)
        canvas?.drawRect(dst, scrimPaint)
    }
}