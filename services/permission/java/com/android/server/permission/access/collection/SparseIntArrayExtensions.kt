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

package com.android.server.permission.access.collection

import android.util.SparseIntArray

inline fun SparseIntArray.allIndexed(predicate: (Int, Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun SparseIntArray.anyIndexed(predicate: (Int, Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun SparseIntArray.forEachIndexed(action: (Int, Int, Int) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseIntArray.forEachReversedIndexed(action: (Int, Int, Int) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun SparseIntArray.getOrPut(key: Int, defaultValue: () -> Int): Int {
    val index = indexOfKey(key)
    return if (index >= 0) {
        valueAt(index)
    } else {
        defaultValue().also { put(key, it) }
    }
}

inline val SparseIntArray.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun SparseIntArray.minusAssign(key: Int) {
    delete(key)
}

inline fun SparseIntArray.noneIndexed(predicate: (Int, Int, Int) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

fun SparseIntArray.remove(key: Int) {
    delete(key)
}

fun SparseIntArray.remove(key: Int, defaultValue: Int): Int {
    val index = indexOfKey(key)
    return if (index >= 0) {
        val oldValue = valueAt(index)
        removeAt(index)
        oldValue
    } else {
        defaultValue
    }
}

inline fun SparseIntArray.removeAllIndexed(predicate: (Int, Int, Int) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun SparseIntArray.retainAllIndexed(predicate: (Int, Int, Int) -> Boolean): Boolean {
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
inline operator fun SparseIntArray.set(key: Int, value: Int) {
    put(key, value)
}

inline val SparseIntArray.size: Int
    get() = size()
