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

inline fun <T, R> IntMap<T>.firstNotNullOfOrNullIndexed(transform: (Int, Int, T) -> R): R? {
    forEachIndexed { index, key, value ->
        transform(index, key, value)?.let {
            return it
        }
    }
    return null
}

inline fun <T> IntMap<T>.forEachIndexed(action: (Int, Int, T) -> Unit) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <T> IntMap<T>.forEachReversedIndexed(action: (Int, Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

fun <T> IntMap<T>?.getWithDefault(key: Int, defaultValue: T): T {
    this ?: return defaultValue
    val index = indexOfKey(key)
    return if (index >= 0) valueAt(index) else defaultValue
}

inline val <T> IntMap<T>.lastIndex: Int
    get() = size - 1

inline fun <T> IntMap<T>.noneIndexed(predicate: (Int, Int, T) -> Boolean): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <T> MutableIntMap<T>.getOrPut(key: Int, defaultValue: () -> T): T {
    get(key)?.let {
        return it
    }
    return defaultValue().also { put(key, it) }
}

operator fun <T> MutableIntMap<T>.minusAssign(key: Int) {
    array.remove(key).also { array.gc() }
}

fun <T> MutableIntMap<T>.putWithDefault(key: Int, value: T, defaultValue: T): T {
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

operator fun <T> MutableIntMap<T>.set(key: Int, value: T) {
    array.put(key, value)
}
