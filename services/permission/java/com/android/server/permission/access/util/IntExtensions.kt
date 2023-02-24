/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.permission.access.util

fun Int.hasAnyBit(bits: Int): Boolean = this and bits != 0

fun Int.hasBits(bits: Int): Boolean = this and bits == bits

infix fun Int.andInv(other: Int): Int = this and other.inv()

inline fun Int.flagsToString(flagToString: (Int) -> String): String {
    var flags = this
    return buildString {
        append("[")
        while (flags != 0) {
            val flag = 1 shl flags.countTrailingZeroBits()
            flags = flags andInv flag
            append(flagToString(flag))
            if (flags != 0) {
                append('|')
            }
        }
        append("]")
    }
}
