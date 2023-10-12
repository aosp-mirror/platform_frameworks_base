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

import android.util.SparseArray

/**
 * Immutable map with index-based access, [Int] keys and mutable data structure values.
 *
 * @see MutableReference
 */
sealed class IntReferenceMap<I : Immutable<M>, M : I>(
    internal val array: SparseArray<MutableReference<I, M>>
) : Immutable<MutableIntReferenceMap<I, M>> {
    val size: Int
        get() = array.size()

    fun isEmpty(): Boolean = array.size() == 0

    operator fun contains(key: Int): Boolean = array.contains(key)

    @Suppress("ReplaceGetOrSet") operator fun get(key: Int): I? = array.get(key)?.get()

    fun indexOfKey(key: Int): Int = array.indexOfKey(key)

    fun keyAt(index: Int): Int = array.keyAt(index)

    fun valueAt(index: Int): I = array.valueAt(index).get()

    override fun toMutable(): MutableIntReferenceMap<I, M> = MutableIntReferenceMap(this)

    override fun toString(): String = array.toString()
}

/**
 * Mutable map with index-based access, [Int] keys and mutable data structure values.
 *
 * @see MutableReference
 */
class MutableIntReferenceMap<I : Immutable<M>, M : I>(
    array: SparseArray<MutableReference<I, M>> = SparseArray()
) : IntReferenceMap<I, M>(array) {
    constructor(
        intReferenceMap: IntReferenceMap<I, M>
    ) : this(
        intReferenceMap.array.clone().apply {
            for (i in 0 until size()) {
                setValueAt(i, valueAt(i).toImmutable())
            }
        }
    )

    @Suppress("ReplaceGetOrSet") fun mutate(key: Int): M? = array.get(key)?.mutate()

    fun put(key: Int, value: M): I? = array.putReturnOld(key, MutableReference(value))?.get()

    fun remove(key: Int): I? = array.removeReturnOld(key).also { array.gc() }?.get()

    fun clear() {
        array.clear()
    }

    fun mutateAt(index: Int): M = array.valueAt(index).mutate()

    fun putAt(index: Int, value: M): I =
        array.setValueAtReturnOld(index, MutableReference(value)).get()

    fun removeAt(index: Int): I = array.removeAtReturnOld(index).also { array.gc() }.get()
}
