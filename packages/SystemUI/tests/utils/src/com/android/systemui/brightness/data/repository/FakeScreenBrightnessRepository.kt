/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.brightness.data.repository

import android.hardware.display.BrightnessInfo
import android.hardware.display.BrightnessInfo.BRIGHTNESS_MAX_REASON_NONE
import android.hardware.display.BrightnessInfo.HIGH_BRIGHTNESS_MODE_OFF
import com.android.systemui.brightness.shared.model.LinearBrightness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class FakeScreenBrightnessRepository(
    initialBrightnessInfo: BrightnessInfo =
        BrightnessInfo(0f, 0f, 1f, HIGH_BRIGHTNESS_MODE_OFF, 1f, BRIGHTNESS_MAX_REASON_NONE)
) : ScreenBrightnessRepository {

    private val brightnessInfo = MutableStateFlow(initialBrightnessInfo)
    private val _temporaryBrightness =
        MutableStateFlow(LinearBrightness(initialBrightnessInfo.brightness))
    val temporaryBrightness = _temporaryBrightness.asStateFlow()
    override val linearBrightness = brightnessInfo.map { LinearBrightness(it.brightness) }
    override val minLinearBrightness = brightnessInfo.map { LinearBrightness(it.brightnessMinimum) }
    override val maxLinearBrightness = brightnessInfo.map { LinearBrightness(it.brightnessMaximum) }

    override suspend fun getMinMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness> {
        return minMaxLinearBrightness()
    }

    private fun minMaxLinearBrightness(): Pair<LinearBrightness, LinearBrightness> {
        return with(brightnessInfo.value) {
            LinearBrightness(brightnessMinimum) to LinearBrightness(brightnessMaximum)
        }
    }

    override fun setTemporaryBrightness(value: LinearBrightness) {
        val bounds = minMaxLinearBrightness()
        val clampedValue = value.clamp(bounds.first, bounds.second)
        _temporaryBrightness.value = clampedValue
    }

    override fun setBrightness(value: LinearBrightness) {
        val bounds = minMaxLinearBrightness()
        val clampedValue = value.clamp(bounds.first, bounds.second)
        _temporaryBrightness.value = clampedValue
        brightnessInfo.value =
            with(brightnessInfo.value) {
                BrightnessInfo(
                    clampedValue.floatValue,
                    brightnessMinimum,
                    brightnessMaximum,
                    highBrightnessMode,
                    highBrightnessTransitionPoint,
                    brightnessMaxReason,
                )
            }
    }

    fun setMinMaxBrightness(min: LinearBrightness, max: LinearBrightness) {
        check(min.floatValue <= max.floatValue)
        val clampedBrightness = LinearBrightness(brightnessInfo.value.brightness).clamp(min, max)
        _temporaryBrightness.value = clampedBrightness
        brightnessInfo.value =
            with(brightnessInfo.value) {
                BrightnessInfo(
                    clampedBrightness.floatValue,
                    min.floatValue,
                    max.floatValue,
                    highBrightnessMode,
                    highBrightnessTransitionPoint,
                    brightnessMaxReason
                )
            }
    }
}
