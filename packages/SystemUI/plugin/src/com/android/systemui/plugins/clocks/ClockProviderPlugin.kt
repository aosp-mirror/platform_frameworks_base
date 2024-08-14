/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package com.android.systemui.plugins.clocks

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import com.android.internal.annotations.Keep
import com.android.systemui.log.core.MessageBuffer
import com.android.systemui.plugins.Plugin
import com.android.systemui.plugins.annotations.GeneratedImport
import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.ProtectedReturn
import com.android.systemui.plugins.annotations.ProvidesInterface
import com.android.systemui.plugins.annotations.SimpleProperty
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Identifies a clock design */
typealias ClockId = String

/** A Plugin which exposes the ClockProvider interface */
@ProtectedInterface
@ProvidesInterface(action = ClockProviderPlugin.ACTION, version = ClockProviderPlugin.VERSION)
interface ClockProviderPlugin : Plugin, ClockProvider {
    companion object {
        const val ACTION = "com.android.systemui.action.PLUGIN_CLOCK_PROVIDER"
        const val VERSION = 1
    }
}

/** Interface for building clocks and providing information about those clocks */
@ProtectedInterface
@GeneratedImport("java.util.List")
@GeneratedImport("java.util.ArrayList")
interface ClockProvider {
    /** Initializes the clock provider with debug log buffers */
    fun initialize(buffers: ClockMessageBuffers?)

    @ProtectedReturn("return new ArrayList<ClockMetadata>();")
    /** Returns metadata for all clocks this provider knows about */
    fun getClocks(): List<ClockMetadata>

    @ProtectedReturn("return null;")
    /** Initializes and returns the target clock design */
    fun createClock(settings: ClockSettings): ClockController?

    @ProtectedReturn("return new ClockPickerConfig(\"\", \"\", \"\", null);")
    /** Settings configuration parameters for the clock */
    fun getClockPickerConfig(id: ClockId): ClockPickerConfig
}

/** Interface for controlling an active clock */
@ProtectedInterface
interface ClockController {
    @get:SimpleProperty
    /** A small version of the clock, appropriate for smaller viewports */
    val smallClock: ClockFaceController

    @get:SimpleProperty
    /** A large version of the clock, appropriate when a bigger viewport is available */
    val largeClock: ClockFaceController

    @get:SimpleProperty
    /** Determines the way the hosting app should behave when rendering either clock face */
    val config: ClockConfig

    @get:SimpleProperty
    /** Events that clocks may need to respond to */
    val events: ClockEvents

    /** Initializes various rendering parameters. If never called, provides reasonable defaults. */
    fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float)

    /** Optional method for dumping debug information */
    fun dump(pw: PrintWriter)
}

/** Interface for a specific clock face version rendered by the clock */
@ProtectedInterface
interface ClockFaceController {
    @get:SimpleProperty
    @Deprecated("Prefer use of layout")
    /** View that renders the clock face */
    val view: View

    @get:SimpleProperty
    /** Layout specification for this clock */
    val layout: ClockFaceLayout

    @get:SimpleProperty
    /** Determines the way the hosting app should behave when rendering this clock face */
    val config: ClockFaceConfig

    @get:SimpleProperty
    /** Events specific to this clock face */
    val events: ClockFaceEvents

    @get:SimpleProperty
    /** Triggers for various animations */
    val animations: ClockAnimations
}

/** For clocks that want to report debug information */
data class ClockMessageBuffers(
    /** Message buffer for general infra */
    val infraMessageBuffer: MessageBuffer,

    /** Message buffer for small clock renering */
    val smallClockMessageBuffer: MessageBuffer,

    /** Message buffer for large clock rendering */
    val largeClockMessageBuffer: MessageBuffer,
)

data class AodClockBurnInModel(val scale: Float, val translationX: Float, val translationY: Float)

/** Specifies layout information for the clock face */
@ProtectedInterface
@GeneratedImport("java.util.ArrayList")
@GeneratedImport("android.view.View")
interface ClockFaceLayout {
    @get:ProtectedReturn("return new ArrayList<View>();")
    /** All clock views to add to the root constraint layout before applying constraints. */
    val views: List<View>

    @ProtectedReturn("return constraints;")
    /** Custom constraints to apply to Lockscreen ConstraintLayout. */
    fun applyConstraints(constraints: ConstraintSet): ConstraintSet

    @ProtectedReturn("return constraints;")
    /** Custom constraints to apply to preview ConstraintLayout. */
    fun applyPreviewConstraints(constraints: ConstraintSet): ConstraintSet

    fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel)
}

/** A ClockFaceLayout that applies the default lockscreen layout to a single view */
class DefaultClockFaceLayout(val view: View) : ClockFaceLayout {
    // both small and large clock should have a container (RelativeLayout in
    // SimpleClockFaceController)
    override val views = listOf(view)

    override fun applyConstraints(constraints: ConstraintSet): ConstraintSet {
        if (views.size != 1) {
            throw IllegalArgumentException(
                "Should have only one container view when using DefaultClockFaceLayout"
            )
        }
        return constraints
    }

    override fun applyPreviewConstraints(constraints: ConstraintSet): ConstraintSet {
        return constraints
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        // Default clock doesn't need detailed control of view
    }
}

/** Events that should call when various rendering parameters change */
@ProtectedInterface
interface ClockEvents {
    @get:ProtectedReturn("return false;")
    /** Set to enable or disable swipe interaction */
    var isReactiveTouchInteractionEnabled: Boolean

    /** Call whenever timezone changes */
    fun onTimeZoneChanged(timeZone: TimeZone)

    /** Call whenever the text time format changes (12hr vs 24hr) */
    fun onTimeFormatChanged(is24Hr: Boolean)

    /** Call whenever the locale changes */
    fun onLocaleChanged(locale: Locale)

    /** Call whenever the color palette should update */
    fun onColorPaletteChanged(resources: Resources)

    /** Call if the seed color has changed and should be updated */
    fun onSeedColorChanged(seedColor: Int?)

    /** Call whenever the weather data should update */
    fun onWeatherDataChanged(data: WeatherData)

    /** Call with alarm information */
    fun onAlarmDataChanged(data: AlarmData)

    /** Call with zen/dnd information */
    fun onZenDataChanged(data: ZenData)

    /** Update reactive axes for this clock */
    fun onReactiveAxesChanged(axes: List<ClockReactiveSetting>)
}

/** Axis setting value for a clock */
data class ClockReactiveSetting(
    /** Axis key; matches ClockReactiveAxis.key */
    val key: String,

    /** Value to set this axis to */
    val value: Float,
)

/** Methods which trigger various clock animations */
@ProtectedInterface
interface ClockAnimations {
    /** Runs an enter animation (if any) */
    fun enter()

    /** Sets how far into AOD the device currently is. */
    fun doze(fraction: Float)

    /** Sets how far into the folding animation the device is. */
    fun fold(fraction: Float)

    /** Runs the battery animation (if any). */
    fun charge()

    /**
     * Runs when the clock's position changed during the move animation.
     *
     * @param fromLeft the [View.getLeft] position of the clock, before it started moving.
     * @param direction the direction in which it is moving. A positive number means right, and
     *   negative means left.
     * @param fraction fraction of the clock movement. 0 means it is at the beginning, and 1 means
     *   it finished moving.
     * @deprecated use {@link #onPositionUpdated(float, float)} instead.
     */
    fun onPositionUpdated(fromLeft: Int, direction: Int, fraction: Float)

    /**
     * Runs when the clock's position changed during the move animation.
     *
     * @param distance is the total distance in pixels to offset the glyphs when animation
     *   completes. Negative distance means we are animating the position towards the center.
     * @param fraction fraction of the clock movement. 0 means it is at the beginning, and 1 means
     *   it finished moving.
     */
    fun onPositionUpdated(distance: Float, fraction: Float)

    /**
     * Runs when swiping clock picker, swipingFraction: 1.0 -> clock is scaled up in the preview,
     * 0.0 -> clock is scaled down in the shade; previewRatio is previewSize / screenSize
     */
    fun onPickerCarouselSwiping(swipingFraction: Float)
}

/** Events that have specific data about the related face */
@ProtectedInterface
interface ClockFaceEvents {
    /** Call every time tick */
    fun onTimeTick()

    /**
     * Region Darkness specific to the clock face.
     * - isRegionDark = dark theme -> clock should be light
     * - !isRegionDark = light theme -> clock should be dark
     */
    fun onRegionDarknessChanged(isRegionDark: Boolean)

    /**
     * Call whenever font settings change. Pass in a target font size in pixels. The specific clock
     * design is allowed to ignore this target size on a case-by-case basis.
     */
    fun onFontSettingChanged(fontSizePx: Float)

    /**
     * Target region information for the clock face. For small clock, this will match the bounds of
     * the parent view mostly, but have a target height based on the height of the default clock.
     * For large clocks, the parent view is the entire device size, but most clocks will want to
     * render within the centered targetRect to avoid obstructing other elements. The specified
     * targetRegion is relative to the parent view.
     */
    fun onTargetRegionChanged(targetRegion: Rect?)

    /** Called to notify the clock about its display. */
    fun onSecondaryDisplayChanged(onSecondaryDisplay: Boolean)
}

/** Tick rates for clocks */
enum class ClockTickRate(val value: Int) {
    PER_MINUTE(2), // Update the clock once per minute.
    PER_SECOND(1), // Update the clock once per second.
    PER_FRAME(0), // Update the clock every second.
}

/** Some data about a clock design */
data class ClockMetadata(val clockId: ClockId)

data class ClockPickerConfig
@JvmOverloads
constructor(
    val id: String,

    /** Localized name of the clock */
    val name: String,

    /** Localized accessibility description for the clock */
    val description: String,

    /* Static & lightweight thumbnail version of the clock */
    val thumbnail: Drawable,

    /** True if the clock will react to tone changes in the seed color */
    val isReactiveToTone: Boolean = true,

    /** True if the clock is capable of changing style in reaction to touches */
    val isReactiveToTouch: Boolean = false,

    /** Font axes that can be modified on this clock */
    val axes: List<ClockReactiveAxis> = listOf(),
)

/** Represents an Axis that can be modified */
data class ClockReactiveAxis(
    /** Axis key, not user renderable */
    val key: String,

    /** Intended mode of user interaction */
    val type: AxisType,

    /** Maximum value the axis supports */
    val maxValue: Float,

    /** Minimum value the axis supports */
    val minValue: Float,

    /** Current value the axis is set to */
    val currentValue: Float,

    /** User-renderable name of the axis */
    val name: String,

    /** Description of the axis */
    val description: String,
)

/** Axis user interaction modes */
enum class AxisType {
    /** Boolean toggle. Swaps between minValue & maxValue */
    Toggle,

    /** Continuous slider between minValue & maxValue */
    Slider,
}

/** Render configuration for the full clock. Modifies the way systemUI behaves with this clock. */
data class ClockConfig(
    val id: String,

    /** Localized name of the clock */
    val name: String,

    /** Localized accessibility description for the clock */
    val description: String,

    /** Transition to AOD should move smartspace like large clock instead of small clock */
    val useAlternateSmartspaceAODTransition: Boolean = false,

    /** Deprecated version of isReactiveToTone; moved to ClockPickerConfig */
    @Deprecated("TODO(b/352049256): Remove in favor of ClockPickerConfig.isReactiveToTone")
    val isReactiveToTone: Boolean = true,

    /** True if the clock is large frame clock, which will use weather in compose. */
    val useCustomClockScene: Boolean = false,
)

/** Render configuration options for a clock face. Modifies the way SystemUI behaves. */
data class ClockFaceConfig(
    /** Expected interval between calls to onTimeTick. Can always reduce to PER_MINUTE in AOD. */
    val tickRate: ClockTickRate = ClockTickRate.PER_MINUTE,

    /** Call to check whether the clock consumes weather data */
    val hasCustomWeatherDataDisplay: Boolean = false,

    /**
     * Whether this clock has a custom position update animation. If true, the keyguard will call
     * `onPositionUpdated` to notify the clock of a position update animation. If false, a default
     * animation will be used (e.g. a simple translation).
     */
    val hasCustomPositionUpdatedAnimation: Boolean = false,

    /** True if the clock is large frame clock, which will use weatherBlueprint in compose. */
    val useCustomClockScene: Boolean = false,
)

/** Structure for keeping clock-specific settings */
@Keep
data class ClockSettings(
    val clockId: ClockId? = null,
    val seedColor: Int? = null,
    val axes: List<ClockReactiveSetting>? = null,
) {
    // Exclude metadata from equality checks
    var metadata: JSONObject = JSONObject()

    companion object {
        private val KEY_CLOCK_ID = "clockId"
        private val KEY_SEED_COLOR = "seedColor"
        private val KEY_METADATA = "metadata"

        fun serialize(setting: ClockSettings?): String {
            if (setting == null) {
                return ""
            }

            // TODO(b/364673977): Serialize axes

            return JSONObject()
                .put(KEY_CLOCK_ID, setting.clockId)
                .put(KEY_SEED_COLOR, setting.seedColor)
                .put(KEY_METADATA, setting.metadata)
                .toString()
        }

        fun deserialize(jsonStr: String?): ClockSettings? {
            if (jsonStr.isNullOrEmpty()) {
                return null
            }

            // TODO(b/364673977): Deserialize axes

            val json = JSONObject(jsonStr)
            val result =
                ClockSettings(
                    if (!json.isNull(KEY_CLOCK_ID)) json.getString(KEY_CLOCK_ID) else null,
                    if (!json.isNull(KEY_SEED_COLOR)) json.getInt(KEY_SEED_COLOR) else null,
                )
            if (!json.isNull(KEY_METADATA)) {
                result.metadata = json.getJSONObject(KEY_METADATA)
            }
            return result
        }
    }
}
