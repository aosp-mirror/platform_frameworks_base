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
 * limitations under the License
 */

package com.android.systemui.util.kotlin

class Utils {
    companion object {
        fun <A, B, C> toTriple(a: A, bc: Pair<B, C>) = Triple(a, bc.first, bc.second)

        fun <A, B, C, D> toQuad(a: A, b: B, c: C, d: D) = Quad(a, b, c, d)
        fun <A, B, C, D> toQuad(a: A, bcd: Triple<B, C, D>) =
            Quad(a, bcd.first, bcd.second, bcd.third)

        fun <A, B, C, D, E> toQuint(a: A, bcde: Quad<B, C, D, E>) =
            Quint(a, bcde.first, bcde.second, bcde.third, bcde.fourth)
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

data class Quint<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
