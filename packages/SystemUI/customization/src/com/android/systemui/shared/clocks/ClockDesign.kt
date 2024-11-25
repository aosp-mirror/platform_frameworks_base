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

package com.android.systemui.shared.clocks

import android.graphics.Point
import android.view.animation.Interpolator
import com.android.app.animation.Interpolators
import com.android.internal.annotations.Keep
import com.android.systemui.monet.Style as MonetStyle
import com.android.systemui.shared.clocks.view.HorizontalAlignment
import com.android.systemui.shared.clocks.view.VerticalAlignment

/** Data format for a simple asset-defined clock */
@Keep
data class ClockDesign(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val large: ClockFace? = null,
    val small: ClockFace? = null,
    @MonetStyle.Type val colorPalette: Int? = null,
)

/** Describes a clock using layers */
@Keep
data class ClockFace(
    val layers: List<ClockLayer> = listOf<ClockLayer>(),
    val layerBounds: LayerBounds = LayerBounds.FIT,
    val wallpaper: String? = null,
    val faceLayout: DigitalFaceLayout? = null,
    val pickerScale: ClockFaceScaleInPicker? = ClockFaceScaleInPicker(1.0f, 1.0f),
)

@Keep data class ClockFaceScaleInPicker(val scaleX: Float, val scaleY: Float)

/** Base Type for a Clock Layer */
@Keep
interface ClockLayer {
    /** Override of face LayerBounds setting for this layer */
    val layerBounds: LayerBounds?
}

/** Clock layer that renders a static asset */
@Keep
data class AssetLayer(
    /** Asset to render in this layer */
    val asset: AssetReference,
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

/** Clock layer that renders the time (or a component of it) using numerals */
@Keep
data class DigitalHandLayer(
    /** See SimpleDateFormat for timespec format info */
    val timespec: DigitalTimespec,
    val style: TextStyle,
    // adoStyle concrete type must match style,
    // cause styles will transition between style and aodStyle
    val aodStyle: TextStyle?,
    val timer: Int? = null,
    override val layerBounds: LayerBounds? = null,
    var faceLayout: DigitalFaceLayout? = null,
    // we pass 12-hour format from json, which will be converted to 24-hour format in codes
    val dateTimeFormat: String,
    val alignment: DigitalAlignment?,
    // ratio of margins to measured size, currently used for handwritten clocks
    val marginRatio: DigitalMarginRatio? = DigitalMarginRatio(),
) : ClockLayer

/** Clock layer that renders the time (or a component of it) using numerals */
@Keep
data class ComposedDigitalHandLayer(
    val customizedView: String? = null,
    /** See SimpleDateFormat for timespec format info */
    val digitalLayers: List<DigitalHandLayer> = listOf<DigitalHandLayer>(),
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

@Keep
data class DigitalAlignment(
    val horizontalAlignment: HorizontalAlignment?,
    val verticalAlignment: VerticalAlignment?,
)

@Keep
data class DigitalMarginRatio(
    val left: Float = 0F,
    val top: Float = 0F,
    val right: Float = 0F,
    val bottom: Float = 0F,
)

/** Clock layer which renders a component of the time using an analog hand */
@Keep
data class AnalogHandLayer(
    val timespec: AnalogTimespec,
    val tickMode: AnalogTickMode,
    val asset: AssetReference,
    val timer: Int? = null,
    val clock_pivot: Point = Point(0, 0),
    val asset_pivot: Point? = null,
    val length: Float = 1f,
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

/** Clock layer which renders the time using an AVD */
@Keep
data class AnimatedHandLayer(
    val timespec: AnalogTimespec,
    val asset: AssetReference,
    val timer: Int? = null,
    override val layerBounds: LayerBounds? = null,
) : ClockLayer

/** A collection of asset references for use in different device modes */
@Keep
data class AssetReference(
    val light: String,
    val dark: String,
    val doze: String? = null,
    val lightTint: String? = null,
    val darkTint: String? = null,
    val dozeTint: String? = null,
)

/**
 * Core TextStyling attributes for text clocks. Both color and sizing information can be applied to
 * either subtype.
 */
@Keep
interface TextStyle {
    // fontSizeScale is a scale factor applied to the default clock's font size.
    val fontSizeScale: Float?
}

/**
 * This specifies a font and styling parameters for that font. This is rendered using a text view
 * and the text animation classes used by the default clock. To ensure default value take effects,
 * all parameters MUST have a default value
 */
@Keep
data class FontTextStyle(
    // Font to load and use in the TextView
    val fontFamily: String? = null,
    val lineHeight: Float? = null,
    val borderWidth: String? = null,
    // ratio of borderWidth / fontSize
    val borderWidthScale: Float? = null,
    // A color literal like `#FF00FF` or a color resource like `@android:color/system_accent1_100`
    val fillColorLight: String? = null,
    // A color literal like `#FF00FF` or a color resource like `@android:color/system_accent1_100`
    val fillColorDark: String? = null,
    override val fontSizeScale: Float? = null,
    // used when alternate in one font file is needed
    var fontFeatureSettings: String? = null,
    val renderType: RenderType = RenderType.STROKE_TEXT,
    val outlineColor: String? = null,
    val transitionDuration: Long = -1L,
    val transitionInterpolator: InterpolatorEnum? = null,
) : TextStyle

/**
 * As an alternative to using a font, we can instead render a digital clock using a set of drawables
 * for each numeral, and optionally a colon. These drawables will be rendered directly after sizing
 * and placing them. This may be easier than generating a font file in some cases, and is provided
 * for ease of use. Unlike fonts, these are not localizable to other numeric systems (like Burmese).
 */
@Keep
data class LottieTextStyle(
    val numbers: List<String> = listOf(),
    // Spacing between numbers, dimension string
    val spacing: String = "0dp",
    // Colon drawable may be omitted if unused in format spec
    val colon: String? = null,
    // key is keypath name to get strokes from lottie, value is the color name to query color in
    // palette, e.g. @android:color/system_accent1_100
    val fillColorLightMap: Map<String, String>? = null,
    val fillColorDarkMap: Map<String, String>? = null,
    override val fontSizeScale: Float? = null,
    val paddingVertical: String = "0dp",
    val paddingHorizontal: String = "0dp",
) : TextStyle

/** Layer sizing mode for the clockface or layer */
enum class LayerBounds {
    /**
     * Sized so the larger dimension matches the allocated space. This results in some of the
     * allocated space being unused.
     */
    FIT,

    /**
     * Sized so the smaller dimension matches the allocated space. This will clip some content to
     * the edges of the space.
     */
    FILL,

    /** Fills the allocated space exactly by stretching the layer */
    STRETCH,
}

/** Ticking mode for analog hands. */
enum class AnalogTickMode {
    SWEEP,
    TICK,
}

/** Timspec options for Analog Hands. Named for tick interval. */
enum class AnalogTimespec {
    SECONDS,
    MINUTES,
    HOURS,
    HOURS_OF_DAY,
    DAY_OF_WEEK,
    DAY_OF_MONTH,
    DAY_OF_YEAR,
    WEEK,
    MONTH,
    TIMER,
}

enum class DigitalTimespec {
    TIME_FULL_FORMAT,
    DIGIT_PAIR,
    FIRST_DIGIT,
    SECOND_DIGIT,
    DATE_FORMAT,
}

enum class DigitalFaceLayout {
    // can only use HH_PAIR, MM_PAIR from DigitalTimespec
    TWO_PAIRS_VERTICAL,
    TWO_PAIRS_HORIZONTAL,
    // can only use HOUR_FIRST_DIGIT, HOUR_SECOND_DIGIT, MINUTE_FIRST_DIGIT, MINUTE_SECOND_DIGIT
    // from DigitalTimespec, used for tabular layout when the font doesn't support tnum
    FOUR_DIGITS_ALIGN_CENTER,
    FOUR_DIGITS_HORIZONTAL,
}

enum class RenderType {
    CHANGE_WEIGHT,
    HOLLOW_TEXT,
    STROKE_TEXT,
    OUTER_OUTLINE_TEXT,
}

enum class InterpolatorEnum(factory: () -> Interpolator) {
    STANDARD({ Interpolators.STANDARD }),
    EMPHASIZED({ Interpolators.EMPHASIZED });

    val interpolator: Interpolator by lazy(factory)
}

fun generateDigitalLayerIdString(layer: DigitalHandLayer): String {
    return if (
        layer.timespec == DigitalTimespec.TIME_FULL_FORMAT ||
            layer.timespec == DigitalTimespec.DATE_FORMAT
    ) {
        layer.timespec.toString()
    } else {
        if ("h" in layer.dateTimeFormat) {
            "HOUR" + "_" + layer.timespec.toString()
        } else {
            "MINUTE" + "_" + layer.timespec.toString()
        }
    }
}
