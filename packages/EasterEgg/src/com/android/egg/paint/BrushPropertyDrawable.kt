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
import android.graphics.PixelFormat.TRANSLUCENT
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

class BrushPropertyDrawable : Drawable {
    val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.BLACK
        it.style = Paint.Style.FILL
    }
    val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
        it.color = Color.RED
        it.style = Paint.Style.FILL
    }

    constructor(context: Context) {
        _size = (24 * context.resources.displayMetrics.density).toInt()
    }

    private var _size = 24
    private var _scale = 1f

    fun setFrameColor(color: Int) {
        framePaint.color = color
        invalidateSelf()
    }

    fun setWellColor(color: Int) {
        wellPaint.color = color
        invalidateSelf()
    }

    fun setWellScale(scale: Float) {
        _scale = scale
        invalidateSelf()
    }

    override fun getIntrinsicWidth(): Int {
        return _size
    }

    override fun getIntrinsicHeight(): Int {
        return _size
    }

    override fun draw(c: Canvas) {
        c.let {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            val inset = _size / 12 // 2dp in a 24x24 icon
            val r = Math.min(w, h) / 2

            c.drawCircle(w/2, h/2, (r - inset) * _scale + 1 , wellPaint)

            val p = Path()
            p.addCircle(w/2, h/2, r, Path.Direction.CCW)
            p.addCircle(w/2, h/2, r - inset, Path.Direction.CW)
            c.drawPath(p, framePaint)
        }
    }

    override fun setAlpha(p0: Int) {
        //
    }

    override fun getOpacity(): Int {
        return TRANSLUCENT
    }

    override fun setColorFilter(p0: ColorFilter?) {
        //
    }

}