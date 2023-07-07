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

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

const val PIf = PI.toFloat()
const val PI2f = (2 * PI).toFloat()

typealias Vec2 = Offset

fun Vec2.str(fmt: String = "%+.2f"): String = "<$fmt,$fmt>".format(x, y)

fun Vec2(x: Float, y: Float): Vec2 = Offset(x, y)

fun Vec2.mag(): Float {
    return getDistance()
}

fun Vec2.distance(other: Vec2): Float {
    return (this - other).mag()
}

fun Vec2.angle(): Float {
    return atan2(y, x)
}

fun Vec2.dot(o: Vec2): Float {
    return x * o.x + y * o.y
}

fun Vec2.product(f: Float): Vec2 {
    return Vec2(x * f, y * f)
}

fun Offset.Companion.makeWithAngleMag(a: Float, m: Float): Vec2 {
    return Vec2(m * cos(a), m * sin(a))
}

fun Vec2.rotate(angle: Float, origin: Vec2 = Vec2.Zero): Offset {
    val translated = this - origin
    return origin +
        Offset(
            (translated.x * cos(angle) - translated.y * sin(angle)),
            (translated.x * sin(angle) + translated.y * cos(angle))
        )
}
