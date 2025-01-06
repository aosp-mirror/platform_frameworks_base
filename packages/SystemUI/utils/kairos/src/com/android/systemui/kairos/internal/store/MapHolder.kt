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

package com.android.systemui.kairos.internal.store

import com.android.systemui.kairos.internal.util.ConcurrentNullableHashMap

@JvmInline
internal value class MapHolder<K, V>(val unwrapped: Map<K, V>) :
    MapK<MapHolder.W, K, V>, Map<K, V> by unwrapped {
    object W
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <K, V> MapK<MapHolder.W, K, V>.asMapHolder(): MapHolder<K, V> =
    this as MapHolder<K, V>

// TODO: preserve insertion order?
internal class ConcurrentHashMapK<K, V>(private val storage: ConcurrentNullableHashMap<K, V>) :
    MutableMapK<MapHolder.W, K, V>, MutableMap<K, V> by storage {

    override fun readOnlyCopy() = MapHolder(storage.toMap())

    override fun asReadOnly(): MapK<MapHolder.W, K, V> = MapHolder(storage)

    class Factory<K> : MutableMapK.Factory<MapHolder.W, K> {
        override fun <V> create(capacity: Int?) =
            ConcurrentHashMapK<K, V>(
                capacity?.let { ConcurrentNullableHashMap(capacity) } ?: ConcurrentNullableHashMap()
            )

        override fun <V> create(input: MapK<MapHolder.W, K, V>) =
            ConcurrentHashMapK(
                ConcurrentNullableHashMap<K, V>().apply {
                    input.asMapHolder().unwrapped.forEach { (k, v) -> set(k, v) }
                }
            )
    }
}
