/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * A background style for smarter-smart-actions.
 *
 * TODO(b/383567383) implement final UX
 */
class MagicActionBackgroundDrawable(context: Context) : Drawable() {

    private var _alpha: Int = 255
    private var _colorFilter: ColorFilter? = null
    private val paint =
        Paint().apply {
            color = context.getColor(com.android.internal.R.color.materialColorPrimaryContainer)
        }

    override fun draw(canvas: Canvas) {
        canvas.drawRect(bounds, paint)
    }

    override fun setAlpha(alpha: Int) {
        _alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        _colorFilter = colorFilter
        invalidateSelf()
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
