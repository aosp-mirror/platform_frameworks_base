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

/**
 * Immutable map with index-based access and mutable data structure values.
 *
 * @see MutableReference
 */
sealed class IndexedReferenceMap<K, I : Immutable<M>, M : I>(
    internal val map: ArrayMap<K, MutableReference<I, M>>
) : Immutable<MutableIndexedReferenceMap<K, I, M>> {
    val size: Int
        get() = map.size

    fun isEmpty(): Boolean = map.isEmpty()

    operator fun contains(key: K): Boolean = map.containsKey(key)

    @Suppress("ReplaceGetOrSet") operator fun get(key: K): I? = map.get(key)?.get()

    fun indexOfKey(key: K): Int = map.indexOfKey(key)

    fun keyAt(index: Int): K = map.keyAt(index)

    fun valueAt(index: Int): I = map.valueAt(index).get()

    override fun toMutable(): MutableIndexedReferenceMap<K, I, M> = MutableIndexedReferenceMap(this)

    override fun toString(): String = map.toString()
}

/**
 * Mutable map with index-based access and mutable data structure values.
 *
 * @see MutableReference
 */
class MutableIndexedReferenceMap<K, I : Immutable<M>, M : I>(
    map: ArrayMap<K, MutableReference<I, M>> = ArrayMap()
) : IndexedReferenceMap<K, I, M>(map) {
    constructor(
        indexedReferenceMap: IndexedReferenceMap<K, I, M>
    ) : this(
        ArrayMap(indexedReferenceMap.map).apply {
            for (i in 0 until size) {
                setValueAt(i, valueAt(i).toImmutable())
            }
        }
    )

    @Suppress("ReplaceGetOrSet") fun mutate(key: K): M? = map.get(key)?.mutate()

    fun put(key: K, value: M): I? = map.put(key, MutableReference(value))?.get()

    fun remove(key: K): I? = map.remove(key)?.get()

    fun clear() {
        map.clear()
    }

    fun mutateAt(index: Int): M = map.valueAt(index).mutate()

    fun putAt(index: Int, value: M): I = map.setValueAt(index, MutableReference(value)).get()

    fun removeAt(index: Int): I = map.removeAt(index).get()
}
