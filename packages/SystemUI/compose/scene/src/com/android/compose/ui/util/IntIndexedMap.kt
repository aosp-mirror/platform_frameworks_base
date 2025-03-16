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

package com.android.compose.ui.util

/**
 * This is a custom implementation that resembles a SortedMap<Int, T> but is based on a simple
 * ArrayList to avoid the allocation overhead and boxing.
 *
 * It can only hold positive keys and 0 and it is only efficient for small keys (0 - ~100), but
 * therefore provides fast operations for small keys.
 */
internal class IntIndexedMap<T> {
    private val arrayList = ArrayList<T?>()
    private var _size = 0
    val size
        get() = _size

    /** Returns the value at [key] or null if the key is not present. */
    operator fun get(key: Int): T? {
        if (key < 0 || key >= arrayList.size) return null
        return arrayList[key]
    }

    /**
     * Sets the value at [key] to [value]. If [key] is larger than the current size of the map, this
     * operation may take up to O(key) time and space. Therefore this data structure is only
     * efficient for small [key] sizes.
     */
    operator fun set(key: Int, value: T?) {
        if (key < 0)
            throw UnsupportedOperationException("This map can only hold positive keys and 0.")
        if (key < arrayList.size) {
            if (arrayList[key] != null && value == null) _size--
            if (arrayList[key] == null && value != null) _size++
            arrayList[key] = value
        } else {
            if (value == null) return
            while (key > arrayList.size) {
                arrayList.add(null)
            }
            _size++
            arrayList.add(value)
        }
    }

    /** Remove value at [key] */
    fun remove(key: Int) {
        if (key >= arrayList.size) return
        this[key] = null
    }

    /** Get the [value] with the smallest [key] of the map. */
    fun first(): T {
        for (i in 0 until arrayList.size) {
            return arrayList[i] ?: continue
        }
        throw NoSuchElementException("The map is empty.")
    }
}
