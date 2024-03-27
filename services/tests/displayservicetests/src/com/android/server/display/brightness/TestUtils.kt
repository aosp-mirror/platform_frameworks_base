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

package com.android.server.display.brightness

import com.android.server.display.HysteresisLevels
import com.android.server.display.config.SensorData

@JvmOverloads
fun createLightSensorControllerConfig(
    initialSensorRate: Int = 1,
    normalSensorRate: Int = 2,
    resetAmbientLuxAfterWarmUpConfig: Boolean = true,
    ambientLightHorizonShort: Int = 1,
    ambientLightHorizonLong: Int = 10_000,
    lightSensorWarmUpTimeConfig: Int = 0,
    weightingIntercept: Int = 10_000,
    ambientBrightnessThresholds: HysteresisLevels = createHysteresisLevels(),
    ambientBrightnessThresholdsIdle: HysteresisLevels = createHysteresisLevels(),
    brighteningLightDebounceConfig: Long = 100_000,
    darkeningLightDebounceConfig: Long = 100_000,
    brighteningLightDebounceConfigIdle: Long = 100_000,
    darkeningLightDebounceConfigIdle: Long = 100_000,
    ambientLightSensor: SensorData = SensorData()
) = LightSensorController.LightSensorControllerConfig(
    initialSensorRate,
    normalSensorRate,
    resetAmbientLuxAfterWarmUpConfig,
    ambientLightHorizonShort,
    ambientLightHorizonLong,
    lightSensorWarmUpTimeConfig,
    weightingIntercept,
    ambientBrightnessThresholds,
    ambientBrightnessThresholdsIdle,
    brighteningLightDebounceConfig,
    darkeningLightDebounceConfig,
    brighteningLightDebounceConfigIdle,
    darkeningLightDebounceConfigIdle,
    ambientLightSensor
)

fun createHysteresisLevels(
    brighteningThresholdsPercentages: FloatArray = floatArrayOf(),
    darkeningThresholdsPercentages: FloatArray = floatArrayOf(),
    brighteningThresholdLevels: FloatArray = floatArrayOf(),
    darkeningThresholdLevels: FloatArray = floatArrayOf(),
    minDarkeningThreshold: Float = 0f,
    minBrighteningThreshold: Float = 0f,
    potentialOldBrightnessRange: Boolean = false
) = HysteresisLevels(
    brighteningThresholdsPercentages,
    darkeningThresholdsPercentages,
    brighteningThresholdLevels,
    darkeningThresholdLevels,
    minDarkeningThreshold,
    minBrighteningThreshold,
    potentialOldBrightnessRange
)