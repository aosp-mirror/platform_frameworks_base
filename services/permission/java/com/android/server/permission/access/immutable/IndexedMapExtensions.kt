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

package com.android.server.permission.access.immutable

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

inline fun <K, V, R> IndexedMap<K, V>.firstNotNullOfOrNullIndexed(transform: (Int, K, V) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let {
            return it
        }
    }
    return null
}

inline fun <K, V> IndexedMap<K, V>.forEachIndexed(action: (Int, K, V) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, V> IndexedMap<K, V>.forEachReversedIndexed(action: (Int, K, V) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

fun <K, V> IndexedMap<K, V>?.getWithDefault(key: K, defaultValue: V): V {
    this ?: return defaultValue
    val index = indexOfKey(key)
    return if (index >= 0) valueAt(index) else defaultValue
}

inline val <K, V> IndexedMap<K, V>.lastIndex: Int
    get() = size - 1

inline fun <K, V> IndexedMap<K, V>.noneIndexed(predicate: (Int, K, V) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, V, R, C : MutableCollection<R>> IndexedMap<K, V>.mapIndexedTo(
    destination: C,
    transform: (Int, K, V) -> R,
): C {
    forEachIndexed { index, key, value -> transform(index, key, value).let { destination += it } }
    return destination
}

inline fun <K, V, R, C : MutableCollection<R>> IndexedMap<K, V>.mapNotNullIndexedTo(
    destination: C,
    transform: (Int, K, V) -> R?
): C {
    forEachIndexed { index, key, value -> transform(index, key, value)?.let { destination += it } }
    return destination
}

inline fun <K, V> MutableIndexedMap<K, V>.getOrPut(key: K, defaultValue: () -> V): V {
    get(key)?.let {
        return it
    }
    return defaultValue().also { put(key, it) }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, V> MutableIndexedMap<K, V>.minusAssign(key: K) {
    remove(key)
}

fun <K, V> MutableIndexedMap<K, V>.putWithDefault(key: K, value: V, defaultValue: V): V {
    val index = indexOfKey(key)
    if (index >= 0) {
        val oldValue = valueAt(index)
        if (value != oldValue) {
            if (value == defaultValue) {
                removeAt(index)
            } else {
                putAt(index, value)
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

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, V> MutableIndexedMap<K, V>.set(key: K, value: V) {
    put(key, value)
}
