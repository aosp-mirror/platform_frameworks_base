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
import android.graphics.drawable.Drawable
import android.view.View
import com.android.systemui.plugins.annotations.ProvidesInterface
import java.io.PrintWriter
import java.util.Locale
import java.util.TimeZone

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
    fun createClock(id: ClockId): Clock

    /** A static thumbnail for rendering in some examples */
    fun getClockThumbnail(id: ClockId): Drawable?
}

/** Interface for controlling an active clock */
interface Clock {
    /** A small version of the clock, appropriate for smaller viewports */
    val smallClock: View

    /** A large version of the clock, appropriate when a bigger viewport is available */
    val largeClock: View

    /** Events that clocks may need to respond to */
    val events: ClockEvents

    /** Triggers for various animations */
    val animations: ClockAnimations

    /** Initializes various rendering parameters. If never called, provides reasonable defaults. */
    fun initialize(resources: Resources, dozeFraction: Float, foldFraction: Float) {
        events.onColorPaletteChanged(resources)
        animations.doze(dozeFraction)
        animations.fold(foldFraction)
        events.onTimeTick()
    }

    /** Optional method for dumping debug information */
    fun dump(pw: PrintWriter) { }
}

/** Events that should call when various rendering parameters change */
interface ClockEvents {
    /** Call every time tick */
    fun onTimeTick()

    /** Call whenever timezone changes */
    fun onTimeZoneChanged(timeZone: TimeZone) { }

    /** Call whenever the text time format changes (12hr vs 24hr) */
    fun onTimeFormatChanged(is24Hr: Boolean) { }

    /** Call whenever the locale changes */
    fun onLocaleChanged(locale: Locale) { }

    /** Call whenever font settings change */
    fun onFontSettingChanged() { }

    /** Call whenever the color palette should update */
    fun onColorPaletteChanged(resources: Resources) { }
}

/** Methods which trigger various clock animations */
interface ClockAnimations {
    /** Runs an enter animation (if any) */
    fun enter() { }

    /** Sets how far into AOD the device currently is. */
    fun doze(fraction: Float) { }

    /** Sets how far into the folding animation the device is. */
    fun fold(fraction: Float) { }

    /** Runs the battery animation (if any). */
    fun charge() { }
}

/** Some data about a clock design */
data class ClockMetadata(
    val clockId: ClockId,
    val name: String
)
