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

import com.android.internal.annotations.Keep
import org.json.JSONArray
import org.json.JSONObject

@Keep
/** Structure for keeping clock-specific settings */
data class ClockSettings(
    val clockId: ClockId? = null,
    val seedColor: Int? = null,
    val axes: List<ClockFontAxisSetting> = listOf(),
) {
    // Exclude metadata from equality checks
    var metadata: JSONObject = JSONObject()

    companion object {
        private val KEY_CLOCK_ID = "clockId"
        private val KEY_SEED_COLOR = "seedColor"
        private val KEY_METADATA = "metadata"
        private val KEY_AXIS_LIST = "axes"

        fun toJson(setting: ClockSettings): JSONObject {
            return JSONObject().apply {
                put(KEY_CLOCK_ID, setting.clockId)
                put(KEY_SEED_COLOR, setting.seedColor)
                put(KEY_METADATA, setting.metadata)
                put(KEY_AXIS_LIST, ClockFontAxisSetting.toJson(setting.axes))
            }
        }

        fun fromJson(json: JSONObject): ClockSettings {
            val clockId = if (!json.isNull(KEY_CLOCK_ID)) json.getString(KEY_CLOCK_ID) else null
            val seedColor = if (!json.isNull(KEY_SEED_COLOR)) json.getInt(KEY_SEED_COLOR) else null
            val axisList = json.optJSONArray(KEY_AXIS_LIST)?.let(ClockFontAxisSetting::fromJson)
            return ClockSettings(clockId, seedColor, axisList ?: listOf()).apply {
                metadata = json.optJSONObject(KEY_METADATA) ?: JSONObject()
            }
        }
    }
}

@Keep
/** Axis setting value for a clock */
data class ClockFontAxisSetting(
    /** Axis key; matches ClockFontAxis.key */
    val key: String,

    /** Value to set this axis to */
    val value: Float,
) {
    companion object {
        private val KEY_AXIS_KEY = "key"
        private val KEY_AXIS_VALUE = "value"

        fun toJson(setting: ClockFontAxisSetting): JSONObject {
            return JSONObject().apply {
                put(KEY_AXIS_KEY, setting.key)
                put(KEY_AXIS_VALUE, setting.value)
            }
        }

        fun toJson(settings: List<ClockFontAxisSetting>): JSONArray {
            return JSONArray().apply {
                for (axis in settings) {
                    put(toJson(axis))
                }
            }
        }

        fun fromJson(jsonObj: JSONObject): ClockFontAxisSetting {
            return ClockFontAxisSetting(
                key = jsonObj.getString(KEY_AXIS_KEY),
                value = jsonObj.getDouble(KEY_AXIS_VALUE).toFloat(),
            )
        }

        fun fromJson(jsonArray: JSONArray): List<ClockFontAxisSetting> {
            val result = mutableListOf<ClockFontAxisSetting>()
            for (i in 0..jsonArray.length() - 1) {
                val obj = jsonArray.getJSONObject(i)
                if (obj == null) continue
                result.add(fromJson(obj))
            }
            return result
        }

        fun toFVar(settings: List<ClockFontAxisSetting>): String {
            val sb = StringBuilder()
            for (axis in settings) {
                if (sb.length > 0) sb.append(", ")
                sb.append("'${axis.key}' ${axis.value.toInt()}")
            }
            return sb.toString()
        }
    }
}
