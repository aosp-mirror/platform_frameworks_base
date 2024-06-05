/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.battery.unified

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import com.android.systemui.battery.unified.BatteryLayersDrawable.Companion.Metrics
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Draws a right-to-left fill inside of the given [framePath]. This fill is designed to exactly fill
 * the usable space inside of [framePath], given that the stroke width of the path is 1.5, and we
 * want an extra 0.5 (canvas units) of a gap between the fill and the stroke
 */
class BatteryFillDrawable(private val framePath: Path) : Drawable() {
    private var hScale = 1f
    private val scaleMatrix = Matrix().also { it.setScale(1f, 1f) }
    private val scaledPath = Path()
    private val scaledFillRect = RectF()
    private var scaledLeftOffset = 0f
    private var scaledRightInset = 0f

    /** Scale this to the viewport so we fill correctly! */
    private val fillRectNotScaled = RectF()
    private var leftInsetNotScaled = 0f
    private var rightInsetNotScaled = 0f

    /**
     * Configure how much space between the battery frame (drawn at 1.5dp stroke width) and the
     * inner fill. This is accomplished by tracing the exact same path as the frame, but using
     * [BlendMode.CLEAR] as the blend mode.
     *
     * This value also affects the overall width of the fill, so it requires us to re-draw
     * everything
     */
    var fillInsetAmount = -1f
        set(value) {
            if (field != value) {
                field = value
                updateInsets()
                updateScale()
                invalidateSelf()
            }
        }

    // Drawable.level cannot be overloaded
    var batteryLevel = 0
        set(value) {
            field = value
            invalidateSelf()
        }

    var fillColor: Int = 0
        set(value) {
            field = value
            fillPaint.color = value
            invalidateSelf()
        }

    private val clearPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.STROKE
            p.blendMode = BlendMode.CLEAR
        }

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).also { p ->
            p.style = Paint.Style.FILL
            p.color = fillColor
        }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        hScale = bounds.right / Metrics.ViewportWidth

        if (bounds.isEmpty) {
            scaleMatrix.setScale(1f, 1f)
        } else {
            scaleMatrix.setScale(
                (bounds.right / Metrics.ViewportWidth),
                (bounds.bottom / Metrics.ViewportHeight)
            )
        }

        updateScale()
    }

    /**
     * To support dynamic insets, we have to keep mutable references to the left/right unscaled
     * insets, as well as the fill rect.
     */
    private fun updateInsets() {
        leftInsetNotScaled = LeftFillOffsetExcludingPadding + fillInsetAmount
        rightInsetNotScaled = RightFillInsetExcludingPadding + fillInsetAmount

        fillRectNotScaled.set(
            leftInsetNotScaled,
            0f,
            Metrics.ViewportWidth - rightInsetNotScaled,
            Metrics.ViewportHeight
        )
    }

    private fun updateScale() {
        framePath.transform(/* matrix = */ scaleMatrix, /* dst = */ scaledPath)
        scaleMatrix.mapRect(/* dst = */ scaledFillRect, /* src = */ fillRectNotScaled)

        scaledLeftOffset = leftInsetNotScaled * hScale
        scaledRightInset = rightInsetNotScaled * hScale

        // stroke width = 1.5 (same as the outer frame) + 2x fillInsetAmount, since N px of padding
        // requires the entire stroke to be 2N px wider
        clearPaint.strokeWidth = (1.5f + 2 * fillInsetAmount) * hScale
    }

    override fun draw(canvas: Canvas) {
        if (batteryLevel == 0) {
            return
        }

        // saveLayer is needed here so we don't clip the other layers of our drawable
        canvas.saveLayer(null, null)

        // Fill from the opposite direction in rtl mode
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            canvas.scale(-1f, 1f, bounds.width() / 2f, bounds.height() / 2f)
        }

        // We need to use 3 draw commands:
        // 1. Clip to the current level
        // 2. Clip anything outside of the path
        // 3. render the fill as a rect the correct size to fit the inner space
        // 4. Clip out the padding between the frame and the fill

        val fillLeft: Int =
            if (batteryLevel == 100) {
                0
            } else {
                val fillFraction = batteryLevel / 100f
                floor(scaledFillRect.width() * (1 - fillFraction)).roundToInt()
            }

        // Clip to the fill level
        canvas.clipOutRect(
            scaledLeftOffset,
            bounds.top.toFloat(),
            scaledLeftOffset + fillLeft,
            bounds.height().toFloat()
        )
        // Clip everything outside of the path
        canvas.clipPath(scaledPath)

        // Draw the fill
        canvas.drawRect(scaledFillRect, fillPaint)

        // Clear around the fill
        canvas.drawPath(scaledPath, clearPaint)

        // Finally, restore the layer
        canvas.restore()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        clearPaint.setColorFilter(colorFilter)
        fillPaint.setColorFilter(colorFilter)
    }

    // unused
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    // unused
    override fun setAlpha(alpha: Int) {}

    companion object {
        // 3.5f =
        //       2.75 (left-most edge of the frame path)
        //     + 0.75 (1/2 of the stroke width)
        private const val LeftFillOffsetExcludingPadding = 3.5f

        // 1.5, calculated the same way, but from the right edge (without the battery cap), which
        // consumes 2 units of width.
        private const val RightFillInsetExcludingPadding = 1.5f
    }
}
