/*
 * Copyright (C) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.common.ui.drawable

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import kotlin.math.min

/** Renders the wrapped [Drawable] as a circle. */
class CircularDrawable(
    drawable: Drawable,
) : DrawableWrapper(drawable) {
    private val path: Path by lazy { Path() }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        updateClipPath()
    }

    override fun draw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(path)
        drawable?.draw(canvas)
        canvas.restore()
    }

    private fun updateClipPath() {
        path.reset()
        path.addCircle(
            bounds.centerX().toFloat(),
            bounds.centerY().toFloat(),
            min(bounds.width(), bounds.height()) / 2f,
            Path.Direction.CW
        )
    }
}
