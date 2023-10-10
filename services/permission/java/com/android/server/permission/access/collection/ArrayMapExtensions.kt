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

import android.util.ArrayMap

inline fun <K, V> ArrayMap<K, V>.allIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, V> ArrayMap<K, V>.anyIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <K, V> ArrayMap<K, V>.forEachIndexed(action: (Int, K, V) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, V> ArrayMap<K, V>.forEachReversedIndexed(action: (Int, K, V) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, V> ArrayMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
    get(key)?.let {
        return it
    }
    return defaultValue().also { put(key, it) }
}

inline val <K, V> ArrayMap<K, V>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, V> ArrayMap<K, V>.minusAssign(key: K) {
    remove(key)
}

inline fun <K, V> ArrayMap<K, V>.noneIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, V> ArrayMap<K, V>.removeAllIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <K, V> ArrayMap<K, V>.retainAllIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
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
inline operator fun <K, V> ArrayMap<K, V>.set(key: K, value: V) {
    put(key, value)
}
