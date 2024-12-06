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

import android.graphics.drawable.Drawable

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
