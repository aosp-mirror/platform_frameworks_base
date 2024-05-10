/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.util.ArraySet

fun <T> arraySetOf(vararg elements: T): ArraySet<T> = ArraySet(elements.asList())

inline fun <T> ArraySet<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value ->
        if (!predicate(index, value)) {
            return false
        }
    }
    return true
}

inline fun <T> ArraySet<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value ->
        if (predicate(index, value)) {
            return true
        }
    }
    return false
}

inline fun <T> ArraySet<T>.forEachIndexed(action: (Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, valueAt(index))
    }
}

inline fun <T> ArraySet<T>.forEachReversedIndexed(action: (Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, valueAt(index))
    }
}

inline val <T> ArraySet<T>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> ArraySet<T>.minusAssign(value: T) {
    remove(value)
}

inline fun <T> ArraySet<T>.noneIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, value ->
        if (predicate(index, value)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> ArraySet<T>.plusAssign(value: T) {
    add(value)
}

inline fun <T> ArraySet<T>.removeAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, value ->
        if (predicate(index, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <T> ArraySet<T>.retainAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, value ->
        if (!predicate(index, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}
