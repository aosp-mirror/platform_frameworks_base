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
 */

package com.android.systemui

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.hardware.graphics.common.AlphaInterpretation
import android.hardware.graphics.common.DisplayDecorationSupport
import android.view.RoundedCorner
import android.view.RoundedCorners

/**
 * When the HWC of the device supports Composition.DISPLAY_DECORATON, we use this layer to draw
 * screen decorations.
 */
class ScreenDecorHwcLayer(context: Context, displayDecorationSupport: DisplayDecorationSupport)
    : DisplayCutoutBaseView(context) {
    public val colorMode: Int
    private val useInvertedAlphaColor: Boolean
    private val color: Int
    private val bgColor: Int
    private val cornerFilter: ColorFilter
    private val cornerBgFilter: ColorFilter
    private val clearPaint: Paint

    private var roundedCornerTopSize = 0
    private var roundedCornerBottomSize = 0
    private var roundedCornerDrawableTop: Drawable? = null
    private var roundedCornerDrawableBottom: Drawable? = null

    init {
        if (displayDecorationSupport.format != PixelFormat.R_8) {
            throw IllegalArgumentException("Attempting to use unsupported mode " +
                    "${PixelFormat.formatToString(displayDecorationSupport.format)}")
        }
        if (DEBUG_COLOR) {
            color = Color.GREEN
            bgColor = Color.TRANSPARENT
            colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            useInvertedAlphaColor = false
        } else {
            colorMode = ActivityInfo.COLOR_MODE_A8
            useInvertedAlphaColor = displayDecorationSupport.alphaInterpretation ==
                    AlphaInterpretation.COVERAGE
            if (useInvertedAlphaColor) {
                color = Color.TRANSPARENT
                bgColor = Color.BLACK
            } else {
                color = Color.BLACK
                bgColor = Color.TRANSPARENT
            }
        }
        cornerFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        cornerBgFilter = PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_OUT)

        clearPaint = Paint()
        clearPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewRootImpl.setDisplayDecoration(true)

        if (useInvertedAlphaColor) {
            paint.set(clearPaint)
        } else {
            paint.color = color
            paint.style = Paint.Style.FILL
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (useInvertedAlphaColor) {
            canvas.drawColor(bgColor)
        }
        // Cutouts are drawn in DisplayCutoutBaseView.onDraw()
        super.onDraw(canvas)
        drawRoundedCorners(canvas)
    }

    private fun drawRoundedCorners(canvas: Canvas) {
        if (roundedCornerTopSize == 0 && roundedCornerBottomSize == 0) {
            return
        }
        var degree: Int
        for (i in RoundedCorner.POSITION_TOP_LEFT
                until RoundedCorners.ROUNDED_CORNER_POSITION_LENGTH) {
            canvas.save()
            degree = getRoundedCornerRotationDegree(90 * i)
            canvas.rotate(degree.toFloat())
            canvas.translate(
                    getRoundedCornerTranslationX(degree).toFloat(),
                    getRoundedCornerTranslationY(degree).toFloat())
            if (i == RoundedCorner.POSITION_TOP_LEFT || i == RoundedCorner.POSITION_TOP_RIGHT) {
                drawRoundedCorner(canvas, roundedCornerDrawableTop, roundedCornerTopSize)
            } else {
                drawRoundedCorner(canvas, roundedCornerDrawableBottom, roundedCornerBottomSize)
            }
            canvas.restore()
        }
    }

    private fun drawRoundedCorner(canvas: Canvas, drawable: Drawable?, size: Int) {
        if (useInvertedAlphaColor) {
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), clearPaint)
            drawable?.colorFilter = cornerBgFilter
        } else {
            drawable?.colorFilter = cornerFilter
        }
        drawable?.draw(canvas)
        // Clear color filter when we are done with drawing.
        drawable?.clearColorFilter()
    }

    private fun getRoundedCornerRotationDegree(defaultDegree: Int): Int {
        return (defaultDegree - 90 * displayRotation + 360) % 360
    }

    private fun getRoundedCornerTranslationX(degree: Int): Int {
        return when (degree) {
            0, 90 -> 0
            180 -> -width
            270 -> -height
            else -> throw IllegalArgumentException("Incorrect degree: $degree")
        }
    }

    private fun getRoundedCornerTranslationY(degree: Int): Int {
        return when (degree) {
            0, 270 -> 0
            90 -> -width
            180 -> -height
            else -> throw IllegalArgumentException("Incorrect degree: $degree")
        }
    }

    /**
     * Update the rounded corner drawables.
     */
    fun updateRoundedCornerDrawable(top: Drawable, bottom: Drawable) {
        roundedCornerDrawableTop = top
        roundedCornerDrawableBottom = bottom
        updateRoundedCornerDrawableBounds()
        invalidate()
    }

    /**
     * Update the rounded corner size.
     */
    fun updateRoundedCornerSize(top: Int, bottom: Int) {
        roundedCornerTopSize = top
        roundedCornerBottomSize = bottom
        updateRoundedCornerDrawableBounds()
        invalidate()
    }

    private fun updateRoundedCornerDrawableBounds() {
        if (roundedCornerDrawableTop != null) {
            roundedCornerDrawableTop?.setBounds(0, 0, roundedCornerTopSize,
                    roundedCornerTopSize)
        }
        if (roundedCornerDrawableBottom != null) {
            roundedCornerDrawableBottom?.setBounds(0, 0, roundedCornerBottomSize,
                    roundedCornerBottomSize)
        }
        invalidate()
    }

    companion object {
        private val DEBUG_COLOR = ScreenDecorations.DEBUG_COLOR
    }
}
