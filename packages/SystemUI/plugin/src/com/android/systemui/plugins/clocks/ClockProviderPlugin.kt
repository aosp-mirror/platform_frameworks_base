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

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.view.View
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.BOTTOM
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import androidx.constraintlayout.widget.ConstraintSet.WRAP_CONTENT
import com.android.internal.annotations.Keep
import com.android.internal.policy.SystemBarUtils
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
import org.json.JSONArray
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
    fun getClockPickerConfig(settings: ClockSettings): ClockPickerConfig
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
    fun initialize(isDarkTheme: Boolean, dozeFraction: Float, foldFraction: Float)

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
    /** Current theme information the clock is using */
    val theme: ThemeConfig

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
) {
    constructor(buffer: MessageBuffer) : this(buffer, buffer, buffer) {}
}

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
    fun applyPreviewConstraints(context: Context, constraints: ConstraintSet): ConstraintSet

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

    override fun applyPreviewConstraints(
        context: Context,
        constraints: ConstraintSet,
    ): ConstraintSet {
        return applyDefaultPreviewConstraints(context, constraints)
    }

    override fun applyAodBurnIn(aodBurnInModel: AodClockBurnInModel) {
        // Default clock doesn't need detailed control of view
    }

    companion object {
        fun applyDefaultPreviewConstraints(
            context: Context,
            constraints: ConstraintSet,
        ): ConstraintSet {
            constraints.apply {
                val lockscreenClockViewLargeId = getId(context, "lockscreen_clock_view_large")
                constrainWidth(lockscreenClockViewLargeId, WRAP_CONTENT)
                constrainHeight(lockscreenClockViewLargeId, WRAP_CONTENT)
                constrainMaxHeight(lockscreenClockViewLargeId, 0)

                val largeClockTopMargin =
                    SystemBarUtils.getStatusBarHeight(context) +
                        getDimen(context, "small_clock_padding_top") +
                        getDimen(context, "keyguard_smartspace_top_offset") +
                        getDimen(context, "date_weather_view_height") +
                        getDimen(context, "enhanced_smartspace_height")
                connect(lockscreenClockViewLargeId, TOP, PARENT_ID, TOP, largeClockTopMargin)
                connect(lockscreenClockViewLargeId, START, PARENT_ID, START)
                connect(lockscreenClockViewLargeId, END, PARENT_ID, END)

                // In preview, we'll show UDFPS icon for UDFPS devices
                // and nothing for non-UDFPS devices,
                // and we're not planning to add this vide in clockHostView
                // so we only need position of device entry icon to constrain clock
                // Copied calculation codes from applyConstraints in DefaultDeviceEntrySection
                val bottomPaddingPx = getDimen(context, "lock_icon_margin_bottom")
                val defaultDensity =
                    DisplayMetrics.DENSITY_DEVICE_STABLE.toFloat() /
                        DisplayMetrics.DENSITY_DEFAULT.toFloat()
                val lockIconRadiusPx = (defaultDensity * 36).toInt()
                val clockBottomMargin = bottomPaddingPx + 2 * lockIconRadiusPx

                connect(lockscreenClockViewLargeId, BOTTOM, PARENT_ID, BOTTOM, clockBottomMargin)
                val smallClockViewId = getId(context, "lockscreen_clock_view")
                constrainWidth(smallClockViewId, WRAP_CONTENT)
                constrainHeight(smallClockViewId, getDimen(context, "small_clock_height"))
                connect(
                    smallClockViewId,
                    START,
                    PARENT_ID,
                    START,
                    getDimen(context, "clock_padding_start") +
                        getDimen(context, "status_view_margin_horizontal"),
                )
                val smallClockTopMargin =
                    getDimen(context, "keyguard_clock_top_margin") +
                        SystemBarUtils.getStatusBarHeight(context)
                connect(smallClockViewId, TOP, PARENT_ID, TOP, smallClockTopMargin)
            }
            return constraints
        }

        fun getId(context: Context, name: String): Int {
            val packageName = context.packageName
            val res = context.packageManager.getResourcesForApplication(packageName)
            val id = res.getIdentifier(name, "id", packageName)
            return id
        }

        fun getDimen(context: Context, name: String): Int {
            val packageName = context.packageName
            val res = context.packageManager.getResourcesForApplication(packageName)
            val id = res.getIdentifier(name, "dimen", packageName)
            return if (id == 0) 0 else res.getDimensionPixelSize(id)
        }
    }
}

/** Events that should call when various rendering parameters change */
@ProtectedInterface
interface ClockEvents {
    @get:ProtectedReturn("return false;")
    /** Set to enable or disable swipe interaction */
    var isReactiveTouchInteractionEnabled: Boolean // TODO(b/364664388): Remove/Rename

    /** Call whenever timezone changes */
    fun onTimeZoneChanged(timeZone: TimeZone)

    /** Call whenever the text time format changes (12hr vs 24hr) */
    fun onTimeFormatChanged(is24Hr: Boolean)

    /** Call whenever the locale changes */
    fun onLocaleChanged(locale: Locale)

    /** Call whenever the weather data should update */
    fun onWeatherDataChanged(data: WeatherData)

    /** Call with alarm information */
    fun onAlarmDataChanged(data: AlarmData)

    /** Call with zen/dnd information */
    fun onZenDataChanged(data: ZenData)

    /** Update reactive axes for this clock */
    fun onFontAxesChanged(axes: List<ClockFontAxisSetting>)
}

/** Axis setting value for a clock */
data class ClockFontAxisSetting(
    /** Axis key; matches ClockFontAxis.key */
    val key: String,

    /** Value to set this axis to */
    val value: Float,
) {
    companion object {
        private val KEY_AXIS_KEY = "key"
        private val KEY_AXIS_VALUE = "value"

        fun toJson(setting: ClockFontAxisSetting): JSONObject {
            return JSONObject().apply {
                put(KEY_AXIS_KEY, setting.key)
                put(KEY_AXIS_VALUE, setting.value)
            }
        }

        fun toJson(settings: List<ClockFontAxisSetting>): JSONArray {
            return JSONArray().apply {
                for (axis in settings) {
                    put(toJson(axis))
                }
            }
        }

        fun fromJson(jsonObj: JSONObject): ClockFontAxisSetting {
            return ClockFontAxisSetting(
                key = jsonObj.getString(KEY_AXIS_KEY),
                value = jsonObj.getDouble(KEY_AXIS_VALUE).toFloat(),
            )
        }

        fun fromJson(jsonArray: JSONArray): List<ClockFontAxisSetting> {
            val result = mutableListOf<ClockFontAxisSetting>()
            for (i in 0..jsonArray.length() - 1) {
                val obj = jsonArray.getJSONObject(i)
                if (obj == null) continue
                result.add(fromJson(obj))
            }
            return result
        }

        fun toFVar(settings: List<ClockFontAxisSetting>): String {
            val sb = StringBuilder()
            for (axis in settings) {
                if (sb.length > 0) sb.append(", ")
                sb.append("'${axis.key}' ${axis.value.toInt()}")
            }
            return sb.toString()
        }
    }
}

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
     * Call whenever the theme or seedColor is updated
     *
     * Theme can be specific to the clock face.
     * - isDarkTheme -> clock should be light
     * - !isDarkTheme -> clock should be dark
     */
    fun onThemeChanged(theme: ThemeConfig)

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

data class ThemeConfig(val isDarkTheme: Boolean, val seedColor: Int?)

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

    /** Font axes that can be modified on this clock */
    val axes: List<ClockFontAxis> = listOf(),
)

/** Represents an Axis that can be modified */
data class ClockFontAxis(
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
) {
    fun toSetting() = ClockFontAxisSetting(key, currentValue)

    companion object {
        fun merge(
            fontAxes: List<ClockFontAxis>,
            axisSettings: List<ClockFontAxisSetting>,
        ): List<ClockFontAxis> {
            val result = mutableListOf<ClockFontAxis>()
            for (axis in fontAxes) {
                val setting = axisSettings.firstOrNull { axis.key == it.key }
                val output = setting?.let { axis.copy(currentValue = it.value) } ?: axis
                result.add(output)
            }
            return result
        }
    }
}

/** Axis user interaction modes */
enum class AxisType {
    /** Continuous range between minValue & maxValue. */
    Float,

    /** Only minValue & maxValue are valid. No intermediate values between them are allowed. */
    Boolean,
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
    val axes: List<ClockFontAxisSetting> = listOf(),
) {
    // Exclude metadata from equality checks
    var metadata: JSONObject = JSONObject()

    companion object {
        private val KEY_CLOCK_ID = "clockId"
        private val KEY_SEED_COLOR = "seedColor"
        private val KEY_METADATA = "metadata"
        private val KEY_AXIS_LIST = "axes"

        fun toJson(setting: ClockSettings): JSONObject {
            return JSONObject().apply {
                put(KEY_CLOCK_ID, setting.clockId)
                put(KEY_SEED_COLOR, setting.seedColor)
                put(KEY_METADATA, setting.metadata)
                put(KEY_AXIS_LIST, ClockFontAxisSetting.toJson(setting.axes))
            }
        }

        fun fromJson(json: JSONObject): ClockSettings {
            val clockId = if (!json.isNull(KEY_CLOCK_ID)) json.getString(KEY_CLOCK_ID) else null
            val seedColor = if (!json.isNull(KEY_SEED_COLOR)) json.getInt(KEY_SEED_COLOR) else null
            val axisList = json.optJSONArray(KEY_AXIS_LIST)?.let(ClockFontAxisSetting::fromJson)
            return ClockSettings(clockId, seedColor, axisList ?: listOf()).apply {
                metadata = json.optJSONObject(KEY_METADATA) ?: JSONObject()
            }
        }
    }
}
