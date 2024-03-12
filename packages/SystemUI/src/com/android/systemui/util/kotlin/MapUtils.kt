/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.util.kotlin

/** Like [mapValues], but discards `null` values returned from [block]. */
fun <K, V, R> Map<K, V>.mapValuesNotNull(block: (Map.Entry<K, V>) -> R?): Map<K, R> = buildMap {
    this@mapValuesNotNull.mapValuesNotNullTo(this, block)
}

/** Like [mapValuesTo], but discards `null` values returned from [block]. */
fun <K, V, R, M : MutableMap<in K, in R>> Map<out K, V>.mapValuesNotNullTo(
    destination: M,
    block: (Map.Entry<K, V>) -> R?,
): M {
    for (entry in this) {
        block(entry)?.also { destination.put(entry.key, it) }
    }
    return destination
}
