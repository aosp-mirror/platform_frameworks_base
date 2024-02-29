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

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import com.android.systemui.battery.unified.BatteryLayersDrawable.Companion.Metrics

/**
 * (Names are hard) this drawable calculates the percent text for inside of the
 * [BatteryLayersDrawable], assuming that there is no other attribution in the foreground. In this
 * case, we can use the maximum font size and center the text in the full render area inside of the
 * frame. After accounting for the stroke width and the insets from there, our rendering area is
 * 18x10 points.
 *
 * See [BatterySpaceSharingPercentTextDrawable] (names are still hard) for the space-sharing
 * approach.
 *
 * Note that these drawing metrics are only tested to work with google-sans BOLD
 */
class BatteryPercentTextOnlyDrawable(font: Typeface) : Drawable() {
    private var hScale = 1f
    private var vScale = 1f

    // range 0-100
    var batteryLevel: Int = 100
        set(value) {
            field = value
            percentText = "$value"
            invalidateSelf()
        }

    private var percentText = "$batteryLevel"

    private val textPaint =
        Paint().also { p ->
            p.textSize = 10f
            p.typeface = font
        }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        vScale = bounds.bottom / Metrics.ViewportHeight
        hScale = bounds.right / Metrics.ViewportWidth

        updateScale()
    }

    private fun updateScale() {
        textPaint.textSize = TextSize * vScale
    }

    override fun draw(canvas: Canvas) {
        val totalAvailableHeight = CanvasHeight * vScale

        // Distribute the vertical whitespace around the text. This is a simplified version of
        // the equation ((C - T) / 2) + T - V, where C == canvas height, T == text height, and V
        // is the vertical nudge.
        val offsetY = (totalAvailableHeight + textPaint.textSize) / 2 - (VerticalNudge * vScale)

        val totalAvailableWidth = CanvasWidth * hScale
        val textWidth = textPaint.measureText(percentText)
        val offsetX = (totalAvailableWidth - textWidth) / 2

        // Draw the text centered in the available area
        canvas.drawText(
            percentText,
            (ViewportInsetLeft * hScale) + offsetX,
            (ViewportInsetTop * vScale) + offsetY,
            textPaint
        )
    }

    override fun setTint(tintColor: Int) {
        textPaint.color = tintColor
        super.setTint(tintColor)
    }

    override fun getOpacity() = PixelFormat.OPAQUE

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    companion object {
        // Based on the 24x14 canvas, we can render in an 18x10 canvas, inset like so:
        const val ViewportInsetLeft = 4f
        const val ViewportInsetRight = 2f
        const val ViewportInsetTop = 2f
        const val CanvasHeight = 10f
        const val CanvasWidth = 18f

        // raise the text up by a smidgen so that it is more centered. Experimentally determined
        const val VerticalNudge = 1.5f

        // Experimentally-determined value
        const val TextSize = 10f
    }
}
