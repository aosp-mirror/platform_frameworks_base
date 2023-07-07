/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access.collection

import android.util.SparseBooleanArray

class IntSet private constructor(
    private val array: SparseBooleanArray
) {
    constructor() : this(SparseBooleanArray())

    val size: Int
        get() = array.size()

    operator fun contains(element: Int): Boolean = array[element]

    fun elementAt(index: Int): Int = array.keyAt(index)

    fun indexOf(element: Int): Int = array.indexOfKey(element)

    fun add(element: Int) {
        array.put(element, true)
    }

    fun remove(element: Int) {
        array.delete(element)
    }

    fun clear() {
        array.clear()
    }

    fun removeAt(index: Int) {
        array.removeAt(index)
    }

    fun copy(): IntSet = IntSet(array.clone())
}

fun IntSet(values: IntArray): IntSet = IntSet().apply{ this += values }

inline fun IntSet.allIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (!predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun IntSet.anyIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return true
        }
    }
    return false
}

inline fun IntSet.forEachIndexed(action: (Int, Int) -> Unit) {
    for (index in 0 until size) {
        action(index, elementAt(index))
    }
}

inline fun IntSet.forEachReversedIndexed(action: (Int, Int) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, elementAt(index))
    }
}

inline val IntSet.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntSet.minus(element: Int): IntSet = copy().apply { this -= element }

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntSet.minusAssign(element: Int) {
    remove(element)
}

inline fun IntSet.noneIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntSet.plus(element: Int): IntSet = copy().apply { this += element }

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntSet.plusAssign(element: Int) {
    add(element)
}

operator fun IntSet.plusAssign(set: IntSet) {
    set.forEachIndexed { _, it -> this += it }
}

operator fun IntSet.plusAssign(array: IntArray) {
    array.forEach { this += it }
}

inline fun IntSet.removeAllIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun IntSet.retainAllIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (!predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}
