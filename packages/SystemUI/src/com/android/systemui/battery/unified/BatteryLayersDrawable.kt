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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.PathParser
import android.view.Gravity
import android.view.View
import com.android.systemui.res.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Custom [Drawable] that manages a list of other drawables which, together, achieve an appropriate
 * view for [BatteryDrawableState].
 *
 * The main elements managed by this drawable are:
 *
 *      1. A battery frame background, which may show a solid fill color
 *      2. The battery frame itself
 *      3. A custom [BatteryFillDrawable], which renders a fill level, appropriately scale and
 *         clipped to the battery percent
 *      4. Percent text
 *      5. An attribution
 *
 * Layers (1) and (2) are loaded directly from xml, as they are static assets. Layer (3) contains a
 * custom [Drawable.draw] implementation and uses the same path as the battery shape to achieve an
 * appropriate fill shape.
 *
 * The text and attribution layers have the following behaviors:
 *
 *      - When text-only or attribute-only, the foreground layer is centered and the maximum size
 *      - When sharing space between the attribute and the text:
 *          - The internal space is divided into 12x10 and 6x6 rectangles
 *          - The attribution is aligned left
 *          - The percent text is scaled based on the number of characters (1,2, or 3) in the string
 */
@Suppress("RtlHardcoded")
class BatteryLayersDrawable(
    private val frameBg: Drawable,
    private val frame: Drawable,
    private val fill: BatteryFillDrawable,
    private val textOnly: BatteryPercentTextOnlyDrawable,
    private val spaceSharingText: BatterySpaceSharingPercentTextDrawable,
    private val attribution: BatteryAttributionDrawable,
    batteryState: BatteryDrawableState,
) : LayerDrawable(arrayOf(frameBg, frame, fill, textOnly, spaceSharingText, attribution)) {

    private val scaleMatrix = Matrix().also { it.setScale(1f, 1f) }

    private val attrFullCanvas = RectF()
    private val attrRightCanvas = RectF()
    private val scaledAttrFullCanvas = RectF()
    private val scaledAttrRightCanvas = RectF()

    var batteryState = batteryState
        set(value) {
            if (field != value) {
                // Update before we set the backing field so we can diff
                handleUpdateState(field, value)
                field = value
                invalidateSelf()
            }
        }

    var colors: BatteryColors = BatteryColors.LightThemeColors
        set(value) {
            field = value
            updateColorProfile(batteryState.hasForegroundContent(), batteryState.color, value)
        }

    init {
        isAutoMirrored = true
        // Initialize the canvas rects since they are not static
        setAttrRects(layoutDirection == View.LAYOUT_DIRECTION_RTL)
    }

    private fun handleUpdateState(old: BatteryDrawableState, new: BatteryDrawableState) {
        if (new.level != old.level) {
            fill.batteryLevel = new.level
            textOnly.batteryLevel = new.level
            spaceSharingText.batteryLevel = new.level
        }

        val shouldUpdateColors =
            new.color != old.color ||
                new.attribution != attribution.drawable ||
                new.hasForegroundContent() != old.hasForegroundContent()

        if (new.attribution != null && new.attribution != attribution.drawable) {
            attribution.drawable = new.attribution
        }

        if (new.hasForegroundContent() != old.hasForegroundContent()) {
            setFillInsets(new.hasForegroundContent())
        }

        // Finally, update colors last if any of the above conditions were met, so that everything
        // is properly tinted
        if (shouldUpdateColors) {
            updateColorProfile(new.hasForegroundContent(), new.color, colors)
        }
    }

    private fun updateColorProfile(
        hasFg: Boolean,
        color: ColorProfile,
        colorInfo: BatteryColors,
    ) {
        frame.setTint(colorInfo.fg)
        frameBg.setTint(colorInfo.bg)
        textOnly.setTint(colorInfo.fg)
        spaceSharingText.setTint(colorInfo.fg)
        attribution.setTint(colorInfo.fg)

        when (color) {
            ColorProfile.None -> {
                fill.fillColor = if (hasFg) colorInfo.fill else colorInfo.fillOnly
            }
            ColorProfile.Active -> {
                fill.fillColor = colorInfo.activeFill
            }
            ColorProfile.Warning -> {
                fill.fillColor = colorInfo.warnFill
            }
            ColorProfile.Error -> {
                fill.fillColor = colorInfo.errorFill
            }
        }
    }

    private fun setFillInsets(
        hasFg: Boolean,
    ) {
        // Extra padding around the fill if there is nothing in the foreground
        fill.fillInsetAmount = if (hasFg) 0f else 1.5f
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        scaleMatrix.setScale(
            bounds.width() / Metrics.ViewportWidth,
            bounds.height() / Metrics.ViewportHeight
        )

        scaleAttributionBounds()
    }

    override fun onLayoutDirectionChanged(layoutDirection: Int): Boolean {
        setAttrRects(layoutDirection == View.LAYOUT_DIRECTION_RTL)
        scaleAttributionBounds()

        return super.onLayoutDirectionChanged(layoutDirection)
    }

    private fun setAttrRects(rtl: Boolean) {
        // Local refs make the math easier to parse
        val full = Metrics.AttrFullCanvasInsets
        val side = Metrics.AttrRightCanvasInsets
        val sideRtl = Metrics.AttrRightCanvasInsetsRtl
        val vh = Metrics.ViewportHeight
        val vw = Metrics.ViewportWidth

        attrFullCanvas.set(
            if (rtl) full.right else full.left,
            full.top,
            vw - if (rtl) full.left else full.right,
            vh - full.bottom,
        )
        attrRightCanvas.set(
            if (rtl) sideRtl.left else side.left,
            side.top,
            vw - (if (rtl) sideRtl.right else side.right),
            vh - side.bottom,
        )
    }

    /** If bounds (i.e., scale), or RTL properties change, we have to recalculate the attr bounds */
    private fun scaleAttributionBounds() {
        scaleMatrix.mapRect(scaledAttrFullCanvas, attrFullCanvas)
        scaleMatrix.mapRect(scaledAttrRightCanvas, attrRightCanvas)
    }

    override fun draw(canvas: Canvas) {
        // 1. Draw the frame bg
        frameBg.draw(canvas)
        // 2. Then the frame itself
        frame.draw(canvas)

        // 3. Fill it the appropriate amount
        fill.draw(canvas)

        // 4. Decide what goes inside
        if (batteryState.showPercent && batteryState.attribution != null) {
            // 4a. percent & attribution. Implies space-sharing

            // Configure the attribute to draw in a smaller bounding box and align left and use
            // floor/ceil math to make sure we get every available pixel
            attribution.gravity = Gravity.LEFT
            attribution.setBounds(
                floor(scaledAttrRightCanvas.left).toInt(),
                floor(scaledAttrRightCanvas.top).toInt(),
                ceil(scaledAttrRightCanvas.right).toInt(),
                ceil(scaledAttrRightCanvas.bottom).toInt(),
            )
            attribution.draw(canvas)

            spaceSharingText.draw(canvas)
        } else if (batteryState.showPercent) {
            // 4b. Percent only
            textOnly.draw(canvas)
        } else if (batteryState.attribution != null) {
            // 4c. Attribution only
            attribution.gravity = Gravity.CENTER
            attribution.setBounds(
                scaledAttrFullCanvas.left.roundToInt(),
                scaledAttrFullCanvas.top.roundToInt(),
                scaledAttrFullCanvas.right.roundToInt(),
                scaledAttrFullCanvas.bottom.roundToInt(),
            )
            attribution.draw(canvas)
        }
    }

    /**
     * This drawable relies on [BatteryColors] to encode all alpha in their values, so we ignore
     * externally-set alpha
     */
    override fun setAlpha(alpha: Int) {}

    /**
     * Interface that describes relevant top-level metrics for the proper rendering of this icon.
     * The overall canvas is defined as ViewportWidth x ViewportHeight, which is hard coded to 24x14
     * points.
     *
     * The attr canvas insets are rect inset definitions. That is, they are defined as l,t,r,b
     * points from the nearest edge. Note that for RTL, we don't actually flip the text since
     * numbers do not reverse for RTL locales.
     */
    interface M {
        val ViewportWidth: Float
        val ViewportHeight: Float

        /**
         * Insets, oriented in the above viewport in LTR, that define the full canvas for a single
         * foreground element. The element will be fit-center and center-aligned on this canvas
         *
         * 18x8 point size
         */
        val AttrFullCanvasInsets: RectF

        /**
         * Insets, oriented in the above viewport in LTR, that define the partial canvas for a
         * foreground element that shares space with the percent text. The element will be
         * fit-center and left-aligned on this canvas.
         *
         * 6x6 point size
         */
        val AttrRightCanvasInsets: RectF

        /**
         * Insets, oriented in the above viewport in RTL, that define the partial canvas for a
         * foreground element that shares space with the percent text. The element will be
         * fit-center and left-aligned on this canvas.
         *
         * 6x6 point size
         */
        val AttrRightCanvasInsetsRtl: RectF
    }

    companion object {
        private val PercentFont = Typeface.create("google-sans", Typeface.BOLD)

        /**
         * Think of this like the `android:<attr>` values in a drawable.xml file. [Metrics] defines
         * relevant canvas and size information for us to layout this cluster of drawables
         */
        val Metrics =
            object : M {
                override val ViewportWidth: Float = 24f
                override val ViewportHeight: Float = 14f

                override val AttrFullCanvasInsets = RectF(4f, 3f, 2f, 3f)
                override val AttrRightCanvasInsets = RectF(16f, 4f, 2f, 4f)
                override val AttrRightCanvasInsetsRtl = RectF(14f, 4f, 4f, 4f)
            }

        /**
         * Create all of the layers needed by [BatteryLayersDrawable]. This class relies on the
         * following resources to exist in order to properly render:
         * - R.drawable.battery_unified_frame_bg
         * - R.drawable.battery_unified_frame
         * - R.string.battery_unified_frame_path_string
         * - GoogleSans bold font
         *
         * See [BatteryDrawableState] for how to set the properties of the resulting class
         */
        @Suppress("UseCompatLoadingForDrawables")
        fun newBatteryDrawable(
            context: Context,
            initialState: BatteryDrawableState = BatteryDrawableState.DefaultInitialState,
        ): BatteryLayersDrawable {
            val framePath =
                PathParser.createPathFromPathData(
                    context.getString(R.string.battery_unified_frame_path_string)
                )

            val frameBg =
                context.getDrawable(R.drawable.battery_unified_frame_bg)
                    ?: throw IllegalStateException("Missing battery_unified_frame_bg.xml")
            val frame =
                context.getDrawable(R.drawable.battery_unified_frame)
                    ?: throw IllegalStateException("Missing battery_unified_frame.xml")
            val fill = BatteryFillDrawable(framePath)
            val textOnly = BatteryPercentTextOnlyDrawable(PercentFont)
            val spaceSharingText = BatterySpaceSharingPercentTextDrawable(PercentFont)
            val attribution = BatteryAttributionDrawable(null)

            return BatteryLayersDrawable(
                frameBg = frameBg,
                frame = frame,
                fill = fill,
                textOnly = textOnly,
                spaceSharingText = spaceSharingText,
                attribution = attribution,
                batteryState = initialState,
            )
        }
    }
}
