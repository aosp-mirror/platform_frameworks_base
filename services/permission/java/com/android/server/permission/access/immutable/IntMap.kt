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

/** Immutable map with index-based access and [Int] keys. */
sealed class IntMap<T>(internal val array: SparseArray<T>) : Immutable<MutableIntMap<T>> {
    val size: Int
        get() = array.size()

    fun isEmpty(): Boolean = array.size() == 0

    operator fun contains(key: Int): Boolean = array.contains(key)

    operator fun get(key: Int): T? = array.get(key)

    fun indexOfKey(key: Int): Int = array.indexOfKey(key)

    fun keyAt(index: Int): Int = array.keyAt(index)

    fun valueAt(index: Int): T = array.valueAt(index)

    override fun toMutable(): MutableIntMap<T> = MutableIntMap(this)

    override fun toString(): String = array.toString()
}

/** Mutable map with index-based access and [Int] keys. */
class MutableIntMap<T>(array: SparseArray<T> = SparseArray()) : IntMap<T>(array) {
    constructor(intMap: IntMap<T>) : this(intMap.array.clone())

    fun put(key: Int, value: T): T? = array.putReturnOld(key, value)

    fun remove(key: Int): T? = array.removeReturnOld(key).also { array.gc() }

    fun clear() {
        array.clear()
    }

    fun putAt(index: Int, value: T): T = array.setValueAtReturnOld(index, value)

    fun removeAt(index: Int): T = array.removeAtReturnOld(index).also { array.gc() }
}

internal fun <T> SparseArray<T>.putReturnOld(key: Int, value: T): T? {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        setValueAt(index, value)
        oldValue
    } else {
        put(key, value)
        null
    }
}

// SparseArray.removeReturnOld() is @hide, so a backup once we move to APIs.
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
internal fun <T> SparseArray<T>.removeReturnOld(key: Int): T? {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        null
    }
}

internal fun <T> SparseArray<T>.setValueAtReturnOld(index: Int, value: T): T {
    val oldValue = valueAt(index)
    setValueAt(index, value)
    return oldValue
}

internal fun <T> SparseArray<T>.removeAtReturnOld(index: Int): T {
    val oldValue = valueAt(index)
    removeAt(index)
    return oldValue
}

internal fun <T> SparseArray<T>.gc() {
    size()
}
