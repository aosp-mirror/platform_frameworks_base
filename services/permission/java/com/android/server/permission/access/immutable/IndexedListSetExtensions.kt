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

inline fun <T> IndexedListSet<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (!predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun <T> IndexedListSet<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return true
        }
    }
    return false
}

inline fun <T> IndexedListSet<T>.forEachIndexed(action: (Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, elementAt(index))
    }
}

inline fun <T> IndexedListSet<T>.forEachReversedIndexed(action: (Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, elementAt(index))
    }
}

inline val <T> IndexedListSet<T>.lastIndex: Int
    get() = size - 1

operator fun <T> IndexedListSet<T>.minus(element: T): MutableIndexedListSet<T> =
    toMutable().apply { this -= element }

inline fun <T> IndexedListSet<T>.noneIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

operator fun <T> IndexedListSet<T>.plus(element: T): MutableIndexedListSet<T> =
    toMutable().apply { this += element }

// Using Int instead of <R> to avoid autoboxing, since we only have the use case for Int.
inline fun <T> IndexedListSet<T>.reduceIndexed(
    initialValue: Int,
    accumulator: (Int, Int, T) -> Int
): Int {
    var value = initialValue
    forEachIndexed { index, element -> value = accumulator(value, index, element) }
    return value
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> MutableIndexedListSet<T>.minusAssign(element: T) {
    remove(element)
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> MutableIndexedListSet<T>.plusAssign(element: T) {
    add(element)
}
