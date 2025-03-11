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

import com.android.systemui.plugins.Plugin
import com.android.systemui.plugins.annotations.GeneratedImport
import com.android.systemui.plugins.annotations.ProtectedInterface
import com.android.systemui.plugins.annotations.ProtectedReturn
import com.android.systemui.plugins.annotations.ProvidesInterface

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

/** Identifies a clock design */
typealias ClockId = String

/** Some data about a clock design */
data class ClockMetadata(val clockId: ClockId)
