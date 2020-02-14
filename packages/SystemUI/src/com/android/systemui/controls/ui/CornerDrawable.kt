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

package com.android.systemui.controls.ui

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper

/**
 * Use a path to add mask for corners around the drawable, to match the radius
 * of the underlying shape.
 */
class CornerDrawable(val wrapped: Drawable, val cornerRadius: Float) : DrawableWrapper(wrapped) {
    val path: Path = Path()

    init {
        val b = getBounds()
        updatePath(RectF(b))
    }

    override fun draw(canvas: Canvas) {
        canvas.clipPath(path)
        super.draw(canvas)
    }

    override fun setBounds(l: Int, t: Int, r: Int, b: Int) {
        updatePath(RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))
        super.setBounds(l, t, r, b)
    }

    override fun setBounds(r: Rect) {
        updatePath(RectF(r))
        super.setBounds(r)
    }

    private fun updatePath(r: RectF) {
        path.reset()
        path.addRoundRect(r, cornerRadius, cornerRadius, Path.Direction.CW)
    }
}
