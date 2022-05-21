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

import com.android.systemui.plugins.annotations.ProvidesInterface
import android.annotation.FloatRange
import android.graphics.drawable.Drawable
import android.view.View

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

    /** Callback to update the clock view to the current time */
    fun onTimeTick()

    /** Sets the level of the AOD transition */
    fun setAodFraction(@FloatRange(from = 0.0, to = 1.0) fraction: Float)
}

/** Some data about a clock design */
data class ClockMetadata(
    val clockId: ClockId,
    val name: String
)