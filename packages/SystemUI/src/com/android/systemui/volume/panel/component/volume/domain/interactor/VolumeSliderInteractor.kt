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
    private val displayValueRange: ClosedFloatingPointRange<Float> = 0f..100f

    /**
     * Translates [volume], that belongs to [volumeRange] to the value that belongs to
     * [displayValueRange].
     */
    fun processVolumeToValue(
        volume: Int,
        volumeRange: ClosedRange<Int>,
    ): Float {
        val currentRangeStart: Float = volumeRange.start.toFloat()
        val targetRangeStart: Float = displayValueRange.start
        val currentRangeLength: Float = (volumeRange.endInclusive.toFloat() - currentRangeStart)
        val targetRangeLength: Float = displayValueRange.endInclusive - targetRangeStart
        if (currentRangeLength == 0f || targetRangeLength == 0f) {
            return 0f
        }
        val volumeFraction: Float = (volume.toFloat() - currentRangeStart) / currentRangeLength
        return targetRangeStart + volumeFraction * targetRangeLength
    }
}
