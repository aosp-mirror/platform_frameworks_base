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
import android.graphics.Rect
import android.graphics.Region
import android.graphics.drawable.Drawable
import android.hardware.graphics.common.AlphaInterpretation
import android.hardware.graphics.common.DisplayDecorationSupport
import android.view.DisplayCutout.BOUNDS_POSITION_BOTTOM
import android.view.DisplayCutout.BOUNDS_POSITION_LEFT
import android.view.DisplayCutout.BOUNDS_POSITION_LENGTH
import android.view.DisplayCutout.BOUNDS_POSITION_TOP
import android.view.DisplayCutout.BOUNDS_POSITION_RIGHT
import android.view.RoundedCorner
import android.view.RoundedCorners
import android.view.Surface
import androidx.annotation.VisibleForTesting
import kotlin.math.ceil
import kotlin.math.floor

/**
 * When the HWC of the device supports Composition.DISPLAY_DECORATON, we use this layer to draw
 * screen decorations.
 */
class ScreenDecorHwcLayer(context: Context, displayDecorationSupport: DisplayDecorationSupport)
    : DisplayCutoutBaseView(context) {
    val colorMode: Int
    private val useInvertedAlphaColor: Boolean
    private val color: Int
    private val bgColor: Int
    private val cornerFilter: ColorFilter
    private val cornerBgFilter: ColorFilter
    private val clearPaint: Paint
    @JvmField val transparentRect: Rect = Rect()
    private val debugTransparentRegionPaint: Paint?
    private val tempRect: Rect = Rect()

    private var hasTopRoundedCorner = false
    private var hasBottomRoundedCorner = false
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
            debugTransparentRegionPaint = Paint().apply {
                color = 0x2f00ff00 // semi-transparent green
                style = Paint.Style.FILL
            }
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
            debugTransparentRegionPaint = null
        }
        cornerFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        cornerBgFilter = PorterDuffColorFilter(bgColor, PorterDuff.Mode.SRC_OUT)

        clearPaint = Paint()
        clearPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        parent.requestTransparentRegion(this)
        if (!DEBUG_COLOR) {
            viewRootImpl.setDisplayDecoration(true)
        }

        if (useInvertedAlphaColor) {
            paint.set(clearPaint)
        } else {
            paint.color = color
            paint.style = Paint.Style.FILL
        }
    }

    override fun onUpdate() {
        parent.requestTransparentRegion(this)
    }

    override fun onDraw(canvas: Canvas) {
        // If updating onDraw, also update gatherTransparentRegion
        if (useInvertedAlphaColor) {
            canvas.drawColor(bgColor)
        }

        // We may clear the color(if useInvertedAlphaColor is true) of the rounded corner rects
        // before drawing rounded corners. If the cutout happens to be inside one of these rects, it
        // will be cleared, so we have to draw rounded corners before cutout.
        drawRoundedCorners(canvas)
        // Cutouts are drawn in DisplayCutoutBaseView.onDraw()
        super.onDraw(canvas)

        debugTransparentRegionPaint?.let {
            canvas.drawRect(transparentRect, it)
        }
    }

    override fun gatherTransparentRegion(region: Region?): Boolean {
        region?.let {
            calculateTransparentRect()
            if (DEBUG_COLOR) {
                // Since we're going to draw a rectangle where the layer would
                // normally be transparent, treat the transparent region as
                // empty. We still want this method to be called, though, so
                // that it calculates the transparent rect at the right time
                // to match !DEBUG_COLOR.
                region.setEmpty()
            } else {
                region.op(transparentRect, Region.Op.INTERSECT)
            }
        }
        // Always return false - views underneath this should always be visible.
        return false
    }

    /**
     * The transparent rect is calculated by subtracting the regions of cutouts, cutout protect and
     * rounded corners from the region with fullscreen display size.
     */
    @VisibleForTesting
    fun calculateTransparentRect() {
        transparentRect.set(0, 0, width, height)

        // Remove cutout region.
        removeCutoutFromTransparentRegion()

        // Remove cutout protection region.
        removeCutoutProtectionFromTransparentRegion()

        // Remove rounded corner region.
        removeRoundedCornersFromTransparentRegion()
    }

    private fun removeCutoutFromTransparentRegion() {
        displayInfo.displayCutout?.let {
                cutout ->
            if (!cutout.boundingRectLeft.isEmpty) {
                transparentRect.left =
                    cutout.boundingRectLeft.right.coerceAtLeast(transparentRect.left)
            }
            if (!cutout.boundingRectTop.isEmpty) {
                transparentRect.top =
                    cutout.boundingRectTop.bottom.coerceAtLeast(transparentRect.top)
            }
            if (!cutout.boundingRectRight.isEmpty) {
                transparentRect.right =
                    cutout.boundingRectRight.left.coerceAtMost(transparentRect.right)
            }
            if (!cutout.boundingRectBottom.isEmpty) {
                transparentRect.bottom =
                    cutout.boundingRectBottom.top.coerceAtMost(transparentRect.bottom)
            }
        }
    }

    private fun removeCutoutProtectionFromTransparentRegion() {
        if (protectionRect.isEmpty) {
            return
        }

        val centerX = protectionRect.centerX()
        val centerY = protectionRect.centerY()
        val scaledDistanceX = (centerX - protectionRect.left) * cameraProtectionProgress
        val scaledDistanceY = (centerY - protectionRect.top) * cameraProtectionProgress
        tempRect.set(
            floor(centerX - scaledDistanceX).toInt(),
            floor(centerY - scaledDistanceY).toInt(),
            ceil(centerX + scaledDistanceX).toInt(),
            ceil(centerY + scaledDistanceY).toInt()
        )

        // Find out which edge the protectionRect belongs and remove that edge from the transparent
        // region.
        val leftDistance = tempRect.left
        val topDistance = tempRect.top
        val rightDistance = width - tempRect.right
        val bottomDistance = height - tempRect.bottom
        val minDistance = minOf(leftDistance, topDistance, rightDistance, bottomDistance)
        when (minDistance) {
            leftDistance -> {
                transparentRect.left = tempRect.right.coerceAtLeast(transparentRect.left)
            }
            topDistance -> {
                transparentRect.top = tempRect.bottom.coerceAtLeast(transparentRect.top)
            }
            rightDistance -> {
                transparentRect.right = tempRect.left.coerceAtMost(transparentRect.right)
            }
            bottomDistance -> {
                transparentRect.bottom = tempRect.top.coerceAtMost(transparentRect.bottom)
            }
        }
    }

    private fun removeRoundedCornersFromTransparentRegion() {
        var hasTopOrBottomCutouts = false
        var hasLeftOrRightCutouts = false
        displayInfo.displayCutout?.let {
                cutout ->
            hasTopOrBottomCutouts = !cutout.boundingRectTop.isEmpty ||
                    !cutout.boundingRectBottom.isEmpty
            hasLeftOrRightCutouts = !cutout.boundingRectLeft.isEmpty ||
                    !cutout.boundingRectRight.isEmpty
        }
        // The goal is to remove the rounded corner areas as small as possible so that we can have a
        // larger transparent region. Therefore, we should always remove from the short edge sides
        // if possible.
        val isShortEdgeTopBottom = width < height
        if (isShortEdgeTopBottom) {
            // Short edges on top & bottom.
            if (!hasTopOrBottomCutouts && hasLeftOrRightCutouts) {
                // If there are cutouts only on left or right edges, remove left and right sides
                // for rounded corners.
                transparentRect.left = getRoundedCornerSizeByPosition(BOUNDS_POSITION_LEFT)
                    .coerceAtLeast(transparentRect.left)
                transparentRect.right =
                    (width - getRoundedCornerSizeByPosition(BOUNDS_POSITION_RIGHT))
                        .coerceAtMost(transparentRect.right)
            } else {
                // If there are cutouts on top or bottom edges or no cutout at all, remove top
                // and bottom sides for rounded corners.
                transparentRect.top = getRoundedCornerSizeByPosition(BOUNDS_POSITION_TOP)
                    .coerceAtLeast(transparentRect.top)
                transparentRect.bottom =
                    (height - getRoundedCornerSizeByPosition(BOUNDS_POSITION_BOTTOM))
                        .coerceAtMost(transparentRect.bottom)
            }
        } else {
            // Short edges on left & right.
            if (hasTopOrBottomCutouts && !hasLeftOrRightCutouts) {
                // If there are cutouts only on top or bottom edges, remove top and bottom sides
                // for rounded corners.
                transparentRect.top = getRoundedCornerSizeByPosition(BOUNDS_POSITION_TOP)
                    .coerceAtLeast(transparentRect.top)
                transparentRect.bottom =
                    (height - getRoundedCornerSizeByPosition(BOUNDS_POSITION_BOTTOM))
                        .coerceAtMost(transparentRect.bottom)
            } else {
                // If there are cutouts on left or right edges or no cutout at all, remove left
                // and right sides for rounded corners.
                transparentRect.left = getRoundedCornerSizeByPosition(BOUNDS_POSITION_LEFT)
                    .coerceAtLeast(transparentRect.left)
                transparentRect.right =
                    (width - getRoundedCornerSizeByPosition(BOUNDS_POSITION_RIGHT))
                        .coerceAtMost(transparentRect.right)
            }
        }
    }

    private fun getRoundedCornerSizeByPosition(position: Int): Int {
        val delta = displayRotation - Surface.ROTATION_0
        return when ((position + delta) % BOUNDS_POSITION_LENGTH) {
            BOUNDS_POSITION_LEFT -> roundedCornerTopSize.coerceAtLeast(roundedCornerBottomSize)
            BOUNDS_POSITION_TOP -> roundedCornerTopSize
            BOUNDS_POSITION_RIGHT -> roundedCornerTopSize.coerceAtLeast(roundedCornerBottomSize)
            BOUNDS_POSITION_BOTTOM -> roundedCornerBottomSize
            else -> throw IllegalArgumentException("Incorrect position: $position")
        }
    }

    private fun drawRoundedCorners(canvas: Canvas) {
        if (!hasTopRoundedCorner && !hasBottomRoundedCorner) {
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
            if (hasTopRoundedCorner && (i == RoundedCorner.POSITION_TOP_LEFT ||
                            i == RoundedCorner.POSITION_TOP_RIGHT)) {
                drawRoundedCorner(canvas, roundedCornerDrawableTop, roundedCornerTopSize)
            } else if (hasBottomRoundedCorner && (i == RoundedCorner.POSITION_BOTTOM_LEFT ||
                            i == RoundedCorner.POSITION_BOTTOM_RIGHT)) {
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
     * Update the rounded corner existence and size.
     */
    fun updateRoundedCornerExistenceAndSize(
        hasTop: Boolean,
        hasBottom: Boolean,
        topSize: Int,
        bottomSize: Int
    ) {
        if (hasTopRoundedCorner == hasTop &&
                hasBottomRoundedCorner == hasBottom &&
                roundedCornerBottomSize == bottomSize &&
                roundedCornerBottomSize == bottomSize) {
            return
        }
        hasTopRoundedCorner = hasTop
        hasBottomRoundedCorner = hasBottom
        roundedCornerTopSize = topSize
        roundedCornerBottomSize = bottomSize
        updateRoundedCornerDrawableBounds()

        // Use requestLayout() to trigger transparent region recalculated
        requestLayout()
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
