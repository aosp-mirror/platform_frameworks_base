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
 * A variant of [BatteryPercentTextOnlyDrawable] with the following differences:
 * 1. It is defined on a canvas of 12x10 (shortened by 6 points horizontally)
 * 2. Because of this, we scale the font according to the number of characters
 *
 * Note that these drawing metrics are only tested to work with google-sans BOLD
 */
class BatterySpaceSharingPercentTextDrawable(font: Typeface) : Drawable() {
    private var verticalNudge = 0f
    private var hScale = 1f
    private var vScale = 1f

    // range 0-100
    var batteryLevel: Int = 88
        set(value) {
            field = value
            percentText = "$value"
            invalidateSelf()
        }

    private var percentText = "$batteryLevel"
        set(value) {
            field = value
            numberOfCharacters = percentText.length
        }

    private var numberOfCharacters = percentText.length
        set(value) {
            if (field != value) {
                field = value
                updateFontSize()
            }
        }

    private val textPaint =
        Paint().also { p ->
            p.textSize = 10f
            p.typeface = font
        }

    private fun updateFontSize() {
        // These values are determined experimentally
        when (numberOfCharacters) {
            3 -> {
                verticalNudge = 1f
                textPaint.textSize = 6f * hScale
            }
            // 1, 2
            else -> {
                verticalNudge = 1.25f
                textPaint.textSize = 9f * hScale
            }
        }
    }

    private fun updateScale() {
        updateFontSize()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        hScale = bounds.right / Metrics.ViewportWidth
        vScale = bounds.bottom / Metrics.ViewportHeight

        updateScale()
    }

    override fun draw(canvas: Canvas) {
        val totalAvailableHeight = CanvasHeight * vScale

        // Distribute the vertical whitespace around the text. This is a simplified version of
        // the equation ((C - T) / 2) + T - V, where C == canvas height, T == text height, and V
        // is the vertical nudge.
        val offsetY = (totalAvailableHeight + textPaint.textSize) / 2 - (verticalNudge * vScale)

        val totalAvailableWidth = CanvasWidth * hScale
        val textWidth = textPaint.measureText(percentText)
        val offsetX = (totalAvailableWidth - textWidth) / 2

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

    override fun setAlpha(p0: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {
        textPaint.colorFilter = colorFilter
    }

    companion object {
        private const val ViewportInsetLeft = 4f
        private const val ViewportInsetTop = 2f

        private const val CanvasWidth = 12f
        private const val CanvasHeight = 10f
    }
}
