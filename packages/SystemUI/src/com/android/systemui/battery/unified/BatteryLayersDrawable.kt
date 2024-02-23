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
import com.android.systemui.res.R
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
 *
 * When [BatteryDrawableState.showErrorState] is true, we will only show either the percent text OR
 * the battery fill, in order to maximize contrast when using the error colors.
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
    private val scaledAttrFullCanvas = RectF(Metrics.AttrFullCanvas)
    private val scaledAttrRightCanvas = RectF(Metrics.AttrRightCanvas)

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
            updateColors(batteryState.showErrorState, value)
        }

    private fun handleUpdateState(old: BatteryDrawableState, new: BatteryDrawableState) {
        if (new.showErrorState != old.showErrorState) {
            updateColors(new.showErrorState, colors)
        }

        if (new.level != old.level) {
            fill.batteryLevel = new.level
            textOnly.batteryLevel = new.level
            spaceSharingText.batteryLevel = new.level
        }

        if (new.attribution != null && new.attribution != attribution.drawable) {
            attribution.drawable = new.attribution
            updateColors(new.showErrorState, colors)
        }

        if (new.hasForegroundContent() != old.hasForegroundContent()) {
            setFillColor(new.hasForegroundContent(), new.showErrorState, colors)
        }
    }

    /** In error states, we don't draw fill unless there is no foreground content (e.g., percent) */
    private fun updateColors(showErrorState: Boolean, colorInfo: BatteryColors) {
        frameBg.setTint(if (showErrorState) colorInfo.errorBackground else colorInfo.bg)
        frame.setTint(colorInfo.fg)
        attribution.setTint(if (showErrorState) colorInfo.errorForeground else colorInfo.fg)
        textOnly.setTint(if (showErrorState) colorInfo.errorForeground else colorInfo.fg)
        spaceSharingText.setTint(if (showErrorState) colorInfo.errorForeground else colorInfo.fg)
        setFillColor(batteryState.hasForegroundContent(), showErrorState, colorInfo)
    }

    /**
     * If there is a foreground layer, then we draw the fill with the low opacity
     * [BatteryColors.fill] color. Otherwise, if there is no other foreground layer, we will use
     * either the error or fillOnly colors for more contrast
     */
    private fun setFillColor(
        hasFg: Boolean,
        error: Boolean,
        colorInfo: BatteryColors,
    ) {
        if (hasFg) {
            fill.fillColor = colorInfo.fill
        } else {
            fill.fillColor = if (error) colorInfo.errorForeground else colorInfo.fillOnly
        }
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)

        scaleMatrix.setScale(
            bounds.width() / Metrics.ViewportWidth,
            bounds.height() / Metrics.ViewportHeight
        )

        // Scale the attribution bounds
        scaleMatrix.mapRect(scaledAttrFullCanvas, Metrics.AttrFullCanvas)
        scaleMatrix.mapRect(scaledAttrRightCanvas, Metrics.AttrRightCanvas)
    }

    override fun draw(canvas: Canvas) {
        // 1. Draw the frame bg
        frameBg.draw(canvas)
        // 2. Then the frame itself
        frame.draw(canvas)

        // 3. Fill it the appropriate amount if non-error state or error + no attribute
        if (!batteryState.showErrorState || !batteryState.hasForegroundContent()) {
            fill.draw(canvas)
        }
        // 4. Decide what goes inside
        if (batteryState.showPercent && batteryState.attribution != null) {
            // 4a. percent & attribution. Implies space-sharing

            // Configure the attribute to draw in a smaller bounding box and align left
            attribution.gravity = Gravity.LEFT
            attribution.setBounds(
                scaledAttrRightCanvas.left.roundToInt(),
                scaledAttrRightCanvas.top.roundToInt(),
                scaledAttrRightCanvas.right.roundToInt(),
                scaledAttrRightCanvas.bottom.roundToInt(),
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

    interface M {
        val ViewportWidth: Float
        val ViewportHeight: Float

        // Bounds, oriented in the above viewport, where we will fit-center and center-align
        // an attribution that is the sole foreground element
        val AttrFullCanvas: RectF
        // Bounds, oriented in the above viewport, where we will fit-center and left-align
        // an attribution that is sharing space with the percent text of the drawable
        val AttrRightCanvas: RectF
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

                /**
                 * Bounds, oriented in the above viewport, where we will fit-center and center-align
                 * an attribution that is the sole foreground element
                 *
                 * 18x8 point size
                 */
                override val AttrFullCanvas: RectF = RectF(4f, 3f, 22f, 11f)
                /**
                 * Bounds, oriented in the above viewport, where we will fit-center and left-align
                 * an attribution that is sharing space with the percent text of the drawable
                 *
                 * 6x6 point size
                 */
                override val AttrRightCanvas: RectF = RectF(16f, 4f, 22f, 10f)
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
