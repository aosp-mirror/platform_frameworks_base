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

import android.util.LongSparseArray

inline fun <T> LongSparseArray<T>.allIndexed(predicate: (Int, Long, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <T> LongSparseArray<T>.anyIndexed(predicate: (Int, Long, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <T> LongSparseArray<T>.forEachIndexed(action: (Int, Long, T) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <T> LongSparseArray<T>.forEachReversedIndexed(action: (Int, Long, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <T> LongSparseArray<T>.getOrPut(key: Long, defaultValue: () -> T): T {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

inline val <T> LongSparseArray<T>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> LongSparseArray<T>.minusAssign(key: Long) {
    delete(key)
}

inline fun <T> LongSparseArray<T>.noneIndexed(predicate: (Int, Long, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <T> LongSparseArray<T>.removeAllIndexed(predicate: (Int, Long, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <T> LongSparseArray<T>.retainAllIndexed(predicate: (Int, Long, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline val <T> LongSparseArray<T>.size: Int
    get() = size()

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> LongSparseArray<T>.set(key: Long, value: T) {
    put(key, value)
}
