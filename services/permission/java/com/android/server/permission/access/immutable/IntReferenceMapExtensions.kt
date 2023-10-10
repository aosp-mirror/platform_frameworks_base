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

inline fun <I : Immutable<M>, M : I> IntReferenceMap<I, M>.allIndexed(
    predicate: (Int, Int, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (!predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <I : Immutable<M>, M : I> IntReferenceMap<I, M>.anyIndexed(
    predicate: (Int, Int, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return true
        }
    }
    return false
}

inline fun <I : Immutable<M>, M : I> IntReferenceMap<I, M>.forEachIndexed(
    action: (Int, Int, I) -> Unit
) {
    for (index in 0 until size) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline fun <I : Immutable<M>, M : I> IntReferenceMap<I, M>.forEachReversedIndexed(
    action: (Int, Int, I) -> Unit
) {
    for (index in lastIndex downTo 0) {
        action(index, keyAt(index), valueAt(index))
    }
}

inline val <I : Immutable<M>, M : I> IntReferenceMap<I, M>.lastIndex: Int
    get() = size - 1

inline fun <I : Immutable<M>, M : I> IntReferenceMap<I, M>.noneIndexed(
    predicate: (Int, Int, I) -> Boolean
): Boolean {
    forEachIndexed { index, key, value ->
        if (predicate(index, key, value)) {
            return false
        }
    }
    return true
}

inline fun <I : Immutable<M>, M : I> MutableIntReferenceMap<I, M>.mutateOrPut(
    key: Int,
    defaultValue: () -> M
): M {
    mutate(key)?.let {
        return it
    }
    return defaultValue().also { put(key, it) }
}

operator fun <I : Immutable<M>, M : I> MutableIntReferenceMap<I, M>.minusAssign(key: Int) {
    array.remove(key).also { array.gc() }
}

operator fun <I : Immutable<M>, M : I> MutableIntReferenceMap<I, M>.set(key: Int, value: M) {
    array.put(key, MutableReference(value))
}
