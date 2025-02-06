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

package com.android.systemui.shared.customization.data

/**
 * Provides current sensor location information in the current screen resolution [scale].
 *
 * @property scale Scale to apply to the sensor location's natural parameters to support different
 *   screen resolutions.
 */
data class SensorLocation(
    private val naturalCenterX: Int,
    private val naturalCenterY: Int,
    private val naturalRadius: Int,
    private val scale: Float = 1f,
) {
    val centerX: Float
        get() {
            return naturalCenterX * scale
        }

    val centerY: Float
        get() {
            return naturalCenterY * scale
        }

    val radius: Float
        get() {
            return naturalRadius * scale
        }

    fun encode(): String {
        return floatArrayOf(
                naturalCenterX.toFloat(),
                naturalCenterY.toFloat(),
                naturalRadius.toFloat(),
                scale,
            )
            .joinToString(DELIMITER)
    }

    companion object {

        private const val DELIMITER: String = ","

        fun decode(encoded: String): SensorLocation {
            val array = encoded.split(DELIMITER).map { it.toFloat() }.toFloatArray()
            return SensorLocation(array[0].toInt(), array[1].toInt(), array[2].toInt(), array[3])
        }
    }
}
