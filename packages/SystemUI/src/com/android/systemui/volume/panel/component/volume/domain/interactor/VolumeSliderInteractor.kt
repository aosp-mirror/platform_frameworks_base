/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.systemui.volume.panel.component.volume.domain.interactor

import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject

/** Converts from slider value to volume and back. */
@VolumePanelScope
class VolumeSliderInteractor @Inject constructor() {

    /** mimic percentage volume setting */
    val displayValueRange: ClosedFloatingPointRange<Float> = 0f..100f

    /**
     * Translates [volume], that belongs to [volumeRange] to the value that belongs to
     * [displayValueRange].
     *
     * [currentValue] is the raw value received from the slider. Returns [currentValue] when it
     * translates to the same volume as [volume] parameter. This ensures smooth slider experience
     * (avoids snapping when the user stops dragging).
     */
    fun processVolumeToValue(
        volume: Int,
        volumeRange: ClosedRange<Int>,
        currentValue: Float?,
        isMuted: Boolean,
    ): Float {
        if (isMuted) {
            return 0f
        }
        val changedVolume: Int? = currentValue?.let { translateValueToVolume(it, volumeRange) }
        return if (volume != volumeRange.start && volume == changedVolume) {
            currentValue
        } else {
            translateToRange(
                currentValue = volume.toFloat(),
                currentRangeStart = volumeRange.start.toFloat(),
                currentRangeEnd = volumeRange.endInclusive.toFloat(),
                targetRangeStart = displayValueRange.start,
                targetRangeEnd = displayValueRange.endInclusive,
            )
        }
    }

    /** Translates [value] from [displayValueRange] to volume that has [volumeRange]. */
    fun translateValueToVolume(
        value: Float,
        volumeRange: ClosedRange<Int>,
    ): Int {
        return translateToRange(
                currentValue = value,
                currentRangeStart = displayValueRange.start,
                currentRangeEnd = displayValueRange.endInclusive,
                targetRangeStart = volumeRange.start.toFloat(),
                targetRangeEnd = volumeRange.endInclusive.toFloat(),
            )
            .toInt()
    }

    /**
     * Translates a value from one range to another.
     *
     * ```
     * Given: currentValue=3, currentRange=[0, 8], targetRange=[0, 100]
     * Result: 37.5
     * ```
     */
    private fun translateToRange(
        currentValue: Float,
        currentRangeStart: Float,
        currentRangeEnd: Float,
        targetRangeStart: Float,
        targetRangeEnd: Float,
    ): Float {
        val currentRangeLength: Float = (currentRangeEnd - currentRangeStart)
        val targetRangeLength: Float = targetRangeEnd - targetRangeStart
        if (currentRangeLength == 0f || targetRangeLength == 0f) {
            return 0f
        }
        val volumeFraction: Float = (currentValue - currentRangeStart) / currentRangeLength
        return targetRangeStart + volumeFraction * targetRangeLength
    }
}
