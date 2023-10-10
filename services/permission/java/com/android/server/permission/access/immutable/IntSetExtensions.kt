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

operator fun IntSet.minus(element: Int): MutableIntSet = toMutable().apply { this -= element }

operator fun IntSet.minusAssign(element: Int) {
    array.delete(element)
}

inline fun IntSet.noneIndexed(predicate: (Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

operator fun IntSet.plus(element: Int): MutableIntSet = toMutable().apply { this += element }

fun MutableIntSet(values: IntArray): MutableIntSet = MutableIntSet().apply { this += values }

operator fun MutableIntSet.plusAssign(element: Int) {
    array.put(element, true)
}

operator fun MutableIntSet.plusAssign(set: IntSet) {
    set.forEachIndexed { _, it -> this += it }
}

operator fun MutableIntSet.plusAssign(array: IntArray) {
    array.forEach { this += it }
}
