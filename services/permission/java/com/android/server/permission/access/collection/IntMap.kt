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

import android.util.SparseArray

typealias IntMap<T> = SparseArray<T>

inline fun <T> IntMap<T>.allIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <T> IntMap<T>.anyIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <T> IntMap<T>.copy(copyValue: (T) -> T): IntMap<T> =
    this.clone().apply {
        forEachValueIndexed { index, value ->
            setValueAt(index, copyValue(value))
        }
    }

inline fun <T, R> IntMap<T>.firstNotNullOfOrNullIndexed(transform: (Int, Int, T) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let { return it }
    }
    return null
}

inline fun <T> IntMap<T>.forEachIndexed(action: (Int, Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <T> IntMap<T>.forEachKeyIndexed(action: (Int, Int) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index))
    }
}

inline fun <T> IntMap<T>.forEachReversedIndexed(action: (Int, Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <T> IntMap<T>.forEachValueIndexed(action: (Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, valueAt(index))
    }
}

inline fun <T> IntMap<T>.getOrPut(key: Int, defaultValue: () -> T): T {
    get(key)?.let { return it }
    return defaultValue().also { put(key, it) }
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> IntMap<T>?.getWithDefault(key: Int, defaultValue: T): T {
    this ?: return defaultValue
    val index = indexOfKey(key)
    return if (index >= 0) valueAt(index) else defaultValue
}

inline val <T> IntMap<T>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> IntMap<T>.minusAssign(key: Int) {
    remove(key)
}

inline fun <T> IntMap<T>.noneIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline fun <T> IntMap<T>.putWithDefault(key: Int, value: T, defaultValue: T): T {
    val index = indexOfKey(key)
    if (index >= 0) {
        val oldValue = valueAt(index)
        if (value != oldValue) {
            if (value == defaultValue) {
                removeAt(index)
            } else {
                setValueAt(index, value)
            }
        }
        return oldValue
    } else {
        if (value != defaultValue) {
            put(key, value)
        }
        return defaultValue
    }
}

// SparseArray.removeReturnOld() is @hide, so a backup once we move to APIs.
fun <T> IntMap<T>.removeReturnOld(key: Int): T? {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        null
    }
}

inline fun <T> IntMap<T>.removeAllIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <T> IntMap<T>.retainAllIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline val <T> IntMap<T>.size: Int
    get() = size()
