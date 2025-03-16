/*
 * Copyright (C) 2024 The Android Open Source Project
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

/**
 * Exposes the rendering capabilities of this clock to SystemUI so that it can be hosted and render
 * correctly in SystemUI's process. Ideally all clocks could be rendered identically, but in
 * practice we different clocks require different behavior from SystemUI.
 */
data class ClockConfig(
    val id: ClockId,

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

/** Render configuration options for a specific clock face. */
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

/** Tick rates for clocks */
enum class ClockTickRate(val value: Int) {
    PER_MINUTE(2), // Update the clock once per minute.
    PER_SECOND(1), // Update the clock once per second.
    PER_FRAME(0), // Update the clock every second.
}
