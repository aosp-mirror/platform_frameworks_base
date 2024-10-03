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

package com.android.systemui.shared.clocks

import android.content.Context
import android.util.TypedValue
import java.util.regex.Pattern

class DimensionParser(private val ctx: Context) {
    fun convert(dimension: String?): Float? {
        if (dimension == null) {
            return null
        }
        return convert(dimension)
    }

    fun convert(dimension: String): Float {
        val metrics = ctx.resources.displayMetrics
        val (value, unit) = parse(dimension)
        return TypedValue.applyDimension(unit, value, metrics)
    }

    fun parse(dimension: String): Pair<Float, Int> {
        val matcher = parserPattern.matcher(dimension)
        if (!matcher.matches()) {
            throw NumberFormatException("Failed to parse '$dimension'")
        }

        val value =
            matcher.group(1)?.toFloat() ?: throw NumberFormatException("Bad value in '$dimension'")
        val unit =
            dimensionMap.get(matcher.group(3) ?: "")
                ?: throw NumberFormatException("Bad unit in '$dimension'")
        return Pair(value, unit)
    }

    private companion object {
        val parserPattern = Pattern.compile("(\\d+(\\.\\d+)?)([a-z]+)")
        val dimensionMap =
            mapOf(
                "dp" to TypedValue.COMPLEX_UNIT_DIP,
                "dip" to TypedValue.COMPLEX_UNIT_DIP,
                "sp" to TypedValue.COMPLEX_UNIT_SP,
                "px" to TypedValue.COMPLEX_UNIT_PX,
                "pt" to TypedValue.COMPLEX_UNIT_PT,
                "mm" to TypedValue.COMPLEX_UNIT_MM,
                "in" to TypedValue.COMPLEX_UNIT_IN,
            )
    }
}
