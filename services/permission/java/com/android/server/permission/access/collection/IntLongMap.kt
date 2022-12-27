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

import android.util.SparseLongArray

typealias IntLongMap = SparseLongArray

inline fun IntLongMap.allIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun IntLongMap.anyIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntLongMap.copy(): IntLongMap = clone()

inline fun <R> IntLongMap.firstNotNullOfOrNullIndexed(transform: (Int, Int, Long) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let { return it }
    }
    return null
}

inline fun IntLongMap.forEachIndexed(action: (Int, Int, Long) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun IntLongMap.forEachKeyIndexed(action: (Int, Int) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index))
    }
}

inline fun IntLongMap.forEachReversedIndexed(action: (Int, Int, Long) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun IntLongMap.forEachValueIndexed(action: (Int, Long) -> Unit) {
    for (index in 0 until size) {
        action(index, valueAt(index))
    }
}

inline fun IntLongMap.getOrPut(key: Int, defaultValue: () -> Long): Long {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntLongMap?.getWithDefault(key: Int, defaultValue: Long): Long {
    this ?: return defaultValue
    return get(key, defaultValue)
}

inline val IntLongMap.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntLongMap.minusAssign(key: Int) {
    delete(key)
}

inline fun IntLongMap.noneIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntLongMap.putWithDefault(key: Int, value: Long, defaultValue: Long): Long {
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

fun IntLongMap.remove(key: Int) {
    delete(key)
}

fun IntLongMap.remove(key: Int, defaultValue: Long): Long {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        defaultValue
    }
}

inline fun IntLongMap.removeAllIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun IntLongMap.retainAllIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntLongMap.set(key: Int, value: Long) {
    put(key, value)
}

inline val IntLongMap.size: Int
    get() = size()
