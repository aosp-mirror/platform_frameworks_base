/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.metadata

import android.content.Intent
import android.os.Bundle

@Suppress("DEPRECATION")
fun Bundle?.contentEquals(other: Bundle?): Boolean {
    if (this == null) return other == null
    if (other == null) return false
    if (keySet() != other.keySet()) return false
    fun Any?.valueEquals(other: Any?) =
        when (this) {
            is Bundle -> other is Bundle && this.contentEquals(other)
            is Intent -> other is Intent && this.filterEquals(other)
            is BooleanArray -> other is BooleanArray && this contentEquals other
            is ByteArray -> other is ByteArray && this contentEquals other
            is CharArray -> other is CharArray && this contentEquals other
            is DoubleArray -> other is DoubleArray && this contentEquals other
            is FloatArray -> other is FloatArray && this contentEquals other
            is IntArray -> other is IntArray && this contentEquals other
            is LongArray -> other is LongArray && this contentEquals other
            is ShortArray -> other is ShortArray && this contentEquals other
            is Array<*> -> other is Array<*> && this contentDeepEquals other
            else -> this == other
        }
    for (key in keySet()) {
        if (!get(key).valueEquals(other.get(key))) return false
    }
    return true
}
