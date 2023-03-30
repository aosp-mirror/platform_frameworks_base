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
package com.android.systemui.plugins

import android.content.res.Resources
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import com.android.internal.annotations.Keep
import com.android.systemui.plugins.annotations.ProvidesInterface
import com.android.systemui.plugins.log.LogBuffer
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/** Identifies a clock design */
typealias ClockId = String

/** A Plugin which exposes the ClockProvider interface */
@ProvidesInterface(action = ClockProviderPlugin.ACTION, version = ClockProviderPlugin.VERSION)
interface ClockProviderPlugin : Plugin, ClockProvider {
    companion object {
        const val ACTION = "com.android.systemui.action.PLUGIN_CLOCK_PROVIDER"
        const val VERSION = 1
    }
}

/** Interface for building clocks and providing information about those clocks */
interface ClockProvider {
    /** Returns metadata for all clocks this provider knows about */
    fun getClocks(): List<ClockMetadata>

    /** Initializes and returns the target clock design */
    @Deprecated("Use overload with ClockSettings")
    fun createClock(id: ClockId): ClockController {
        return createClock(ClockSettings(id, null))
    }

    /** Initializes and returns the target clock design */
    fun createClock(settings: ClockSettings): ClockController

    /** A static thumbnail for rendering in some examples */
    fun getClockThumbnail(id: ClockId): Drawable?
}

/** Interface for controlling an active clock */
interface ClockController {
    /** A small version of the clock, appropriate for smaller viewports */
    val smallClock: ClockFaceController

    /** A large version of the clock, appropriate when a bigger viewport is available */
    val largeClock: ClockFaceController

    /** Events that clocks may need to respond to */
    val events: ClockEvents

    /** Triggers for various animations */
    val animations: ClockAnimations

    /** Initializes various rendering parameters. If never called, provides reasonable defaults. */
    fun initialize(
        resources: Resources,
        dozeFraction: Float,
        foldFraction: Float,
    ) {
        events.onColorPaletteChanged(resources)
        animations.doze(dozeFraction)
        animations.fold(foldFraction)
        smallClock.events.onTimeTick()
        largeClock.events.onTimeTick()
    }

    /** Optional method for dumping debug information */
    fun dump(pw: PrintWriter) {}
}

/** Interface for a specific clock face version rendered by the clock */
interface ClockFaceController {
    /** View that renders the clock face */
    val view: View

    /** Events specific to this clock face */
    val events: ClockFaceEvents

    /** Some clocks may log debug information */
    var logBuffer: LogBuffer?
}

/** Events that should call when various rendering parameters change */
interface ClockEvents {
    /** Call whenever timezone changes */
    fun onTimeZoneChanged(timeZone: TimeZone) {}

    /** Call whenever the text time format changes (12hr vs 24hr) */
    fun onTimeFormatChanged(is24Hr: Boolean) {}

    /** Call whenever the locale changes */
    fun onLocaleChanged(locale: Locale) {}

    /** Call whenever the color palette should update */
    fun onColorPaletteChanged(resources: Resources) {}

    /** Call if the seed color has changed and should be updated */
    fun onSeedColorChanged(seedColor: Int?) {}

    /** Call whenever the weather data should update */
    fun onWeatherDataChanged(data: WeatherData) {}
}

/** Methods which trigger various clock animations */
interface ClockAnimations {
    /** Runs an enter animation (if any) */
    fun enter() {}

    /** Sets how far into AOD the device currently is. */
    fun doze(fraction: Float) {}

    /** Sets how far into the folding animation the device is. */
    fun fold(fraction: Float) {}

    /** Runs the battery animation (if any). */
    fun charge() {}

    /** Move the clock, for example, if the notification tray appears in split-shade mode. */
    fun onPositionUpdated(fromRect: Rect, toRect: Rect, fraction: Float) {}

    /**
     * Whether this clock has a custom position update animation. If true, the keyguard will call
     * `onPositionUpdated` to notify the clock of a position update animation. If false, a default
     * animation will be used (e.g. a simple translation).
     */
    val hasCustomPositionUpdatedAnimation
        get() = false
}

/** Events that have specific data about the related face */
interface ClockFaceEvents {
    /** Call every time tick */
    fun onTimeTick() {}

    /** Expected interval between calls to onTimeTick. Can always reduce to PER_MINUTE in AOD. */
    val tickRate: ClockTickRate
        get() = ClockTickRate.PER_MINUTE

    /** Region Darkness specific to the clock face */
    fun onRegionDarknessChanged(isDark: Boolean) {}

    /**
     * Call whenever font settings change. Pass in a target font size in pixels. The specific clock
     * design is allowed to ignore this target size on a case-by-case basis.
     */
    fun onFontSettingChanged(fontSizePx: Float) {}

    /**
     * Target region information for the clock face. For small clock, this will match the bounds of
     * the parent view mostly, but have a target height based on the height of the default clock.
     * For large clocks, the parent view is the entire device size, but most clocks will want to
     * render within the centered targetRect to avoid obstructing other elements. The specified
     * targetRegion is relative to the parent view.
     */
    fun onTargetRegionChanged(targetRegion: Rect?) {}
}

/** Tick rates for clocks */
enum class ClockTickRate(val value: Int) {
    PER_MINUTE(2), // Update the clock once per minute.
    PER_SECOND(1), // Update the clock once per second.
    PER_FRAME(0), // Update the clock every second.
}

/** Some data about a clock design */
data class ClockMetadata(
    val clockId: ClockId,
    val name: String,
)

/** Structure for keeping clock-specific settings */
@Keep
data class ClockSettings(
    val clockId: ClockId? = null,
    val seedColor: Int? = null,
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

            val json = JSONObject(jsonStr)
            val result =
                ClockSettings(
                    if (!json.isNull(KEY_CLOCK_ID)) json.getString(KEY_CLOCK_ID) else null,
                    if (!json.isNull(KEY_SEED_COLOR)) json.getInt(KEY_SEED_COLOR) else null
                )
            if (!json.isNull(KEY_METADATA)) {
                result.metadata = json.getJSONObject(KEY_METADATA)
            }
            return result
        }
    }
}
