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

inline fun <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.allIndexed(
    predicate: (Int, K, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.anyIndexed(
    predicate: (Int, K, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.forEachIndexed(
    action: (Int, K, I) -> Unit
) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.forEachReversedIndexed(
    action: (Int, K, I) -> Unit
) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline val <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.lastIndex: Int
    get() = size - 1

inline fun <K, I : Immutable<M>, M : I> IndexedReferenceMap<K, I, M>.noneIndexed(
    predicate: (Int, K, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <K, I : Immutable<M>, M : I> MutableIndexedReferenceMap<K, I, M>.mutateOrPut(
    key: K,
    defaultValue: () -> M
): M {
    mutate(key)?.let {
        return it
    }
    return defaultValue().also { put(key, it) }
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, I : Immutable<M>, M : I> MutableIndexedReferenceMap<K, I, M>.minusAssign(
    key: K
) {
    remove(key)
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <K, I : Immutable<M>, M : I> MutableIndexedReferenceMap<K, I, M>.set(
    key: K,
    value: M
) {
    put(key, value)
}
