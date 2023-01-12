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

import android.util.ArrayMap

typealias IndexedMap<K, V> = ArrayMap<K, V>

inline fun <K, V> IndexedMap<K, V>.allIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, V> IndexedMap<K, V>.anyIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <K, V> IndexedMap<K, V>.copy(copyValue: (V) -> V): IndexedMap<K, V> =
    IndexedMap(this).apply {
        forEachValueIndexed { index, value ->
            setValueAt(index, copyValue(value))
        }
    }

inline fun <K, V, R> IndexedMap<K, V>.firstNotNullOfOrNullIndexed(transform: (Int, K, V) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let { return it }
    }
    return null
}

inline fun <K, V> IndexedMap<K, V>.forEachIndexed(action: (Int, K, V) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, V> IndexedMap<K, V>.forEachKeyIndexed(action: (Int, K) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index))
    }
}

inline fun <K, V> IndexedMap<K, V>.forEachReversedIndexed(action: (Int, K, V) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, V> IndexedMap<K, V>.forEachValueIndexed(action: (Int, V) -> Unit) {
    for (index in 0 until size) {
        action(index, valueAt(index))
    }
}

inline fun <K, V> IndexedMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
    get(key)?.let { return it }
    return defaultValue().also { put(key, it) }
}

@Suppress("NOTHING_TO_INLINE")
inline fun <K, V> IndexedMap<K, V>?.getWithDefault(key: K, defaultValue: V): V {
    this ?: return defaultValue
    val index = indexOfKey(key)
    return if (index >= 0) valueAt(index) else defaultValue
}

inline val <K, V> IndexedMap<K, V>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, V> IndexedMap<K, V>.minusAssign(key: K) {
    remove(key)
}

inline fun <K, V> IndexedMap<K, V>.noneIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline fun <K, V> IndexedMap<K, V>.putWithDefault(key: K, value: V, defaultValue: V): V {
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

inline fun <K, V> IndexedMap<K, V>.removeAllIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <K, V> IndexedMap<K, V>.retainAllIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <K, V, R> IndexedMap<K, V>.mapIndexed(transform: (Int, K, V) -> R): IndexedList<R> =
    IndexedList<R>().also { destination ->
        forEachIndexed { index, key, value ->
            transform(index, key, value).let { destination += it }
        }
    }

inline fun <K, V, R> IndexedMap<K, V>.mapNotNullIndexed(
    transform: (Int, K, V) -> R?
): IndexedList<R> =
    IndexedList<R>().also { destination ->
        forEachIndexed { index, key, value ->
            transform(index, key, value)?.let { destination += it }
        }
    }

inline fun <K, V, R> IndexedMap<K, V>.mapNotNullIndexedToSet(
    transform: (Int, K, V) -> R?
): IndexedSet<R> =
    IndexedSet<R>().also { destination ->
        forEachIndexed { index, key, value ->
            transform(index, key, value)?.let { destination += it }
        }
    }

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, V> IndexedMap<K, V>.set(key: K, value: V) {
    put(key, value)
}
