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

import android.util.ArraySet
import com.android.server.permission.access.collection.forEachIndexed

fun <T> indexedSetOf(vararg elements: T): IndexedSet<T> =
    MutableIndexedSet(ArraySet(elements.asList()))

inline fun <T> IndexedSet<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (!predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun <T> IndexedSet<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return true
        }
    }
    return false
}

inline fun <T> IndexedSet<T>.forEachIndexed(action: (Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, elementAt(index))
    }
}

inline fun <T> IndexedSet<T>.forEachReversedIndexed(action: (Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, elementAt(index))
    }
}

inline val <T> IndexedSet<T>.lastIndex: Int
    get() = size - 1

operator fun <T> IndexedSet<T>.minus(element: T): MutableIndexedSet<T> =
    toMutable().apply { this -= element }

inline fun <T> IndexedSet<T>.noneIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

operator fun <T> IndexedSet<T>.plus(element: T): MutableIndexedSet<T> =
    toMutable().apply { this += element }

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> MutableIndexedSet<T>.minusAssign(element: T) {
    remove(element)
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> MutableIndexedSet<T>.plusAssign(element: T) {
    add(element)
}

operator fun <T> MutableIndexedSet<T>.plusAssign(collection: Collection<T>) {
    collection.forEach { this += it }
}
