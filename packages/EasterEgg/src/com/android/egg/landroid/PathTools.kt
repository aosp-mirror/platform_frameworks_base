/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.egg.landroid

import android.util.Log
import androidx.compose.ui.graphics.Path
import kotlin.math.cos
import kotlin.math.sin

fun createPolygon(radius: Float, sides: Int): Path {
    return Path().apply {
        moveTo(radius, 0f)
        val angleStep = PI2f / sides
        for (i in 1 until sides) {
            lineTo(radius * cos(angleStep * i), radius * sin(angleStep * i))
        }
        close()
    }
}

fun createPolygonPoints(radius: Float, sides: Int): List<Vec2> {
    val angleStep = PI2f / sides
    return (0 until sides).map { i ->
        Vec2(radius * cos(angleStep * i), radius * sin(angleStep * i))
    }
}

fun createStar(radius1: Float, radius2: Float, points: Int): Path {
    return Path().apply {
        val angleStep = PI2f / points
        moveTo(radius1, 0f)
        lineTo(radius2 * cos(angleStep * (0.5f)), radius2 * sin(angleStep * (0.5f)))
        for (i in 1 until points) {
            lineTo(radius1 * cos(angleStep * i), radius1 * sin(angleStep * i))
            lineTo(radius2 * cos(angleStep * (i + 0.5f)), radius2 * sin(angleStep * (i + 0.5f)))
        }
        close()
    }
}

fun Path.parseSvgPathData(d: String) {
    Regex("([A-Za-z])\\s*([-.,0-9e ]+)").findAll(d.trim()).forEach {
        val cmd = it.groups[1]!!.value
        val args =
            it.groups[2]?.value?.split(Regex("\\s+"))?.map { v -> v.toFloat() } ?: emptyList()
        // Log.d("Landroid", "cmd = $cmd, args = " + args.joinToString(","))
        when (cmd) {
            "M" -> moveTo(args[0], args[1])
            "C" -> cubicTo(args[0], args[1], args[2], args[3], args[4], args[5])
            "L" -> lineTo(args[0], args[1])
            "l" -> relativeLineTo(args[0], args[1])
            "Z" -> close()
            else -> Log.v("Landroid", "unsupported SVG command: $cmd")
        }
    }
}
