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
 * limitations under the License.
 */

package com.android.systemui.util.kotlin

import android.util.Log
import java.util.Optional

/**
 * If [value] is not null, then returns block(value). Otherwise returns null.
 */
inline fun <T : Any, R> transform(value: T?, block: (T) -> R): R? = value?.let(block)

/**
 * Assists type-checking to unpack a Java Optional into T?
 */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> Optional<T>.getOrNull(): T? = orElse(null)

/**
 * Utility method to check if a value that is technically nullable is actually null. If it is null,
 * this will crash development builds (but just log on production/droidfood builds). It can be used
 * as a first step to verify if a nullable value can be made non-nullable instead.
 */
fun <T> expectNotNull(logTag: String, name: String, nullable: T?) {
    if (nullable == null) {
        Log.wtf(logTag, "Expected value of $name to not be null.")
    }
}
