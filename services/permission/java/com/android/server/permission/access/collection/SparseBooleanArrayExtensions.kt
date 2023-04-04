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

inline fun SparseBooleanArray.allIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun SparseBooleanArray.anyIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun SparseBooleanArray.forEachIndexed(action: (Int, Int, Boolean) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseBooleanArray.forEachReversedIndexed(action: (Int, Int, Boolean) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseBooleanArray.getOrPut(key: Int, defaultValue: () -> Boolean): Boolean {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

inline val SparseBooleanArray.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun SparseBooleanArray.minusAssign(key: Int) {
    delete(key)
}

inline fun SparseBooleanArray.noneIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

fun SparseBooleanArray.remove(key: Int) {
    delete(key)
}

fun SparseBooleanArray.remove(key: Int, defaultValue: Boolean): Boolean {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        defaultValue
    }
}

inline fun SparseBooleanArray.removeAllIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun SparseBooleanArray.retainAllIndexed(predicate: (Int, Int, Boolean) -> Boolean): Boolean {
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
inline operator fun SparseBooleanArray.set(key: Int, value: Boolean) {
    put(key, value)
}

inline val SparseBooleanArray.size: Int
    get() = size()
