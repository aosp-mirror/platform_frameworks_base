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

package com.android.server.permission.access.immutable

import android.util.ArrayMap

/** Immutable map with index-based access. */
sealed class IndexedMap<K, V>(internal val map: ArrayMap<K, V>) :
    Immutable<MutableIndexedMap<K, V>> {
    val size: Int
        get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    operator fun contains(key: K): Boolean = map.containsKey(key)

    @Suppress("ReplaceGetOrSet") operator fun get(key: K): V? = map.get(key)

    fun indexOfKey(key: K): Int = map.indexOfKey(key)

    fun keyAt(index: Int): K = map.keyAt(index)

    fun valueAt(index: Int): V = map.valueAt(index)

    override fun toMutable(): MutableIndexedMap<K, V> = MutableIndexedMap(this)

    override fun toString(): String = map.toString()
}

/** Mutable map with index-based access. */
class MutableIndexedMap<K, V>(map: ArrayMap<K, V> = ArrayMap()) : IndexedMap<K, V>(map) {
    constructor(indexedMap: IndexedMap<K, V>) : this(ArrayMap(indexedMap.map))

    fun put(key: K, value: V): V? = map.put(key, value)

    fun remove(key: K): V? = map.remove(key)

    fun clear() {
        map.clear()
    }

    fun putAt(index: Int, value: V): V = map.setValueAt(index, value)

    fun removeAt(index: Int): V = map.removeAt(index)
}
