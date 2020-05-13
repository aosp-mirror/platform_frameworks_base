/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.util.animation

/**
 * A class responsible for caching view Measurements which guarantees that we always obtain a value
 */
class GuaranteedMeasurementCache constructor(
    private val baseCache : MeasurementCache,
    private val inputMapper: (MeasurementInput) -> MeasurementInput,
    private val measurementProvider: (MeasurementInput) -> MeasurementOutput?
) : MeasurementCache {

    override fun obtainMeasurement(input: MeasurementInput) : MeasurementOutput {
        val mappedInput = inputMapper.invoke(input)
        if (!baseCache.contains(mappedInput)) {
            var measurement = measurementProvider.invoke(mappedInput)
            if (measurement != null) {
                // Only cache measurings that actually have a size
                baseCache.putMeasurement(mappedInput, measurement)
            } else {
                measurement = MeasurementOutput(0, 0)
            }
            return measurement
        } else {
            return baseCache.obtainMeasurement(mappedInput)
        }
    }

    override fun contains(input: MeasurementInput): Boolean {
        return baseCache.contains(inputMapper.invoke(input))
    }

    override fun putMeasurement(input: MeasurementInput, output: MeasurementOutput) {
        if (output.measuredWidth == 0 || output.measuredHeight == 0) {
            // Only cache measurings that actually have a size
            return;
        }
        val remappedInput = inputMapper.invoke(input)
        baseCache.putMeasurement(remappedInput, output)
    }
}

/**
 * A base implementation class responsible for caching view Measurements
 */
class BaseMeasurementCache : MeasurementCache {
    private val dataCache: MutableMap<MeasurementInput, MeasurementOutput> = mutableMapOf()

    override fun obtainMeasurement(input: MeasurementInput) : MeasurementOutput {
        val measurementOutput = dataCache[input]
        if (measurementOutput == null) {
            return MeasurementOutput(0, 0)
        } else {
            return measurementOutput
        }
    }

    override fun contains(input: MeasurementInput) : Boolean {
        return dataCache[input] != null
    }

    override fun putMeasurement(input: MeasurementInput, output: MeasurementOutput) {
        dataCache[input] = output
    }
}

interface MeasurementCache {
    fun obtainMeasurement(input: MeasurementInput) : MeasurementOutput
    fun contains(input: MeasurementInput) : Boolean
    fun putMeasurement(input: MeasurementInput, output: MeasurementOutput)
}

