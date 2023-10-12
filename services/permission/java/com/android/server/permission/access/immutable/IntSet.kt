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

import android.util.SparseBooleanArray

/** Immutable set with index-based access and [Int] elements. */
sealed class IntSet(internal val array: SparseBooleanArray) : Immutable<MutableIntSet> {
    val size: Int
        get() = array.size()

    fun isEmpty(): Boolean = array.size() == 0

    operator fun contains(element: Int): Boolean = array.contains(element)

    fun indexOf(element: Int): Int = array.indexOfKey(element)

    fun elementAt(index: Int): Int = array.keyAt(index)

    override fun toMutable(): MutableIntSet = MutableIntSet(this)

    override fun toString(): String = array.toString()
}

/** Mutable set with index-based access and [Int] elements. */
class MutableIntSet(array: SparseBooleanArray = SparseBooleanArray()) : IntSet(array) {
    constructor(intSet: IntSet) : this(intSet.array.clone())

    fun add(element: Int): Boolean =
        if (array.contains(element)) {
            false
        } else {
            array.put(element, true)
            true
        }

    fun remove(element: Int): Boolean {
        val index = array.indexOfKey(element)
        return if (index >= 0) {
            array.removeAt(index)
            true
        } else {
            false
        }
    }

    fun clear() {
        array.clear()
    }

    fun removeAt(index: Int) {
        array.removeAt(index)
    }
}

// Unlike SparseArray, SparseBooleanArray is missing this method.
private fun SparseBooleanArray.contains(key: Int): Boolean = indexOfKey(key) >= 0
