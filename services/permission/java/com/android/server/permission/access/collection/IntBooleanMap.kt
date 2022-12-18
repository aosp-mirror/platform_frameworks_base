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

import android.util.SparseBooleanArray

typealias IntBooleanMap = SparseBooleanArray

inline fun IntBooleanMap.allIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun IntBooleanMap.anyIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntBooleanMap.copy(): IntBooleanMap = clone()

inline fun <R> IntBooleanMap.firstNotNullOfOrNullIndexed(transform: (Int, Int, Boolean) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let { return it }
    }
    return null
}

inline fun IntBooleanMap.forEachIndexed(action: (Int, Int, Boolean) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun IntBooleanMap.forEachKeyIndexed(action: (Int, Int) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index))
    }
}

inline fun IntBooleanMap.forEachReversedIndexed(action: (Int, Int, Boolean) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun IntBooleanMap.forEachValueIndexed(action: (Int, Boolean) -> Unit) {
    for (index in 0 until size) {
        action(index, valueAt(index))
    }
}

inline fun IntBooleanMap.getOrPut(key: Int, defaultValue: () -> Boolean): Boolean {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntBooleanMap?.getWithDefault(key: Int, defaultValue: Boolean): Boolean {
    this ?: return defaultValue
    return get(key, defaultValue)
}

inline val IntBooleanMap.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun IntBooleanMap.minusAssign(key: Int) {
    delete(key)
}

inline fun IntBooleanMap.noneIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntBooleanMap.putWithDefault(key: Int, value: Boolean, defaultValue: Boolean): Boolean {
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

fun IntBooleanMap.remove(key: Int) {
    delete(key)
}

fun IntBooleanMap.remove(key: Int, defaultValue: Boolean): Boolean {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        defaultValue
    }
}

inline fun IntBooleanMap.removeAllIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun IntBooleanMap.retainAllIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
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
inline operator fun IntBooleanMap.set(key: Int, value: Boolean) {
    put(key, value)
}

inline val IntBooleanMap.size: Int
    get() = size()
