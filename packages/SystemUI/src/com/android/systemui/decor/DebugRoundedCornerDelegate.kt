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

package com.android.systemui.decor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.util.Size
import java.io.PrintWriter

/**
 * Rounded corner delegate that handles incoming debug commands and can convert them to path
 * drawables to be shown instead of the system-defined rounded corners.
 *
 * These debug corners are expected to supersede the system-defined corners
 */
class DebugRoundedCornerDelegate : RoundedCornerResDelegate {
    override var hasTop: Boolean = false
        private set
    override var topRoundedDrawable: Drawable? = null
        private set
    override var topRoundedSize: Size = Size(0, 0)
        private set

    override var hasBottom: Boolean = false
        private set
    override var bottomRoundedDrawable: Drawable? = null
        private set
    override var bottomRoundedSize: Size = Size(0, 0)
        private set

    override var physicalPixelDisplaySizeRatio: Float = 1f
        set(value) {
            if (field == value) {
                return
            }
            field = value
            reloadMeasures()
        }

    var color: Int = Color.RED
        set(value) {
            if (field == value) {
                return
            }

            field = value
            paint.color = field
        }

    var paint =
        Paint().apply {
            color = Color.RED
            style = Paint.Style.FILL
        }

    override fun updateDisplayUniqueId(newDisplayUniqueId: String?, newReloadToken: Int?) {
        // nop -- debug corners draw the same on every display
    }

    fun applyNewDebugCorners(
        topCorner: DebugRoundedCornerModel,
        bottomCorner: DebugRoundedCornerModel,
    ) {
        hasTop = true
        topRoundedDrawable = topCorner.toPathDrawable(paint)
        topRoundedSize = topCorner.size()

        hasBottom = true
        bottomRoundedDrawable = bottomCorner.toPathDrawable(paint)
        bottomRoundedSize = bottomCorner.size()
    }

    /**
     * Remove accumulated debug state by clearing out the drawables and setting [hasTop] and
     * [hasBottom] to false.
     */
    fun removeDebugState() {
        hasTop = false
        topRoundedDrawable = null
        topRoundedSize = Size(0, 0)

        hasBottom = false
        bottomRoundedDrawable = null
        bottomRoundedSize = Size(0, 0)
    }

    /**
     * Scaling here happens when the display resolution is changed. This logic is exactly the same
     * as in [RoundedCornerResDelegateImpl]
     */
    private fun reloadMeasures() {
        topRoundedDrawable?.let { topRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight) }
        bottomRoundedDrawable?.let {
            bottomRoundedSize = Size(it.intrinsicWidth, it.intrinsicHeight)
        }

        if (physicalPixelDisplaySizeRatio != 1f) {
            if (topRoundedSize.width != 0) {
                topRoundedSize =
                    Size(
                        (physicalPixelDisplaySizeRatio * topRoundedSize.width + 0.5f).toInt(),
                        (physicalPixelDisplaySizeRatio * topRoundedSize.height + 0.5f).toInt()
                    )
            }
            if (bottomRoundedSize.width != 0) {
                bottomRoundedSize =
                    Size(
                        (physicalPixelDisplaySizeRatio * bottomRoundedSize.width + 0.5f).toInt(),
                        (physicalPixelDisplaySizeRatio * bottomRoundedSize.height + 0.5f).toInt()
                    )
            }
        }
    }

    fun dump(pw: PrintWriter) {
        pw.println("DebugRoundedCornerDelegate state:")
        pw.println("  hasTop=$hasTop")
        pw.println("  hasBottom=$hasBottom")
        pw.println("  topRoundedSize(w,h)=(${topRoundedSize.width},${topRoundedSize.height})")
        pw.println(
            "  bottomRoundedSize(w,h)=(${bottomRoundedSize.width},${bottomRoundedSize.height})"
        )
        pw.println("  physicalPixelDisplaySizeRatio=$physicalPixelDisplaySizeRatio")
    }
}

/** Encapsulates the data coming in from the command line args and turns into a [PathDrawable] */
data class DebugRoundedCornerModel(
    val path: Path,
    val width: Int,
    val height: Int,
    val scaleX: Float,
    val scaleY: Float,
) {
    fun size() = Size(width, height)

    fun toPathDrawable(paint: Paint) =
        PathDrawable(
            path,
            width,
            height,
            scaleX,
            scaleY,
            paint,
        )
}

/**
 * PathDrawable accepts paths from the command line via [DebugRoundedCornerModel], and renders them
 * in the canvas provided by the screen decor rounded corner provider
 */
class PathDrawable(
    val path: Path,
    val width: Int,
    val height: Int,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val paint: Paint,
) : Drawable() {
    private var cf: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        if (scaleX != 1f || scaleY != 1f) {
            canvas.scale(scaleX, scaleY)
        }
        canvas.drawPath(path, paint)
    }

    override fun getIntrinsicHeight(): Int = height
    override fun getIntrinsicWidth(): Int = width

    override fun getOpacity(): Int = PixelFormat.OPAQUE

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        cf = colorFilter
    }
}
