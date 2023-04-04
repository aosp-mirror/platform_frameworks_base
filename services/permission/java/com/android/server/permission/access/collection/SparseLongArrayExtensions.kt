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

inline fun SparseLongArray.allIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun SparseLongArray.anyIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun SparseLongArray.forEachIndexed(action: (Int, Int, Long) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseLongArray.forEachReversedIndexed(action: (Int, Int, Long) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseLongArray.getOrPut(key: Int, defaultValue: () -> Long): Long {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

inline val SparseLongArray.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun SparseLongArray.minusAssign(key: Int) {
    delete(key)
}

inline fun SparseLongArray.noneIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

fun SparseLongArray.remove(key: Int) {
    delete(key)
}

fun SparseLongArray.remove(key: Int, defaultValue: Long): Long {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        defaultValue
    }
}

inline fun SparseLongArray.removeAllIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun SparseLongArray.retainAllIndexed(predicate: (Int, Int, Long) -> Boolean): Boolean {
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
inline operator fun SparseLongArray.set(key: Int, value: Long) {
    put(key, value)
}

inline val SparseLongArray.size: Int
    get() = size()
