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

inline fun <T> List<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (!predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun <T> List<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return true
        }
    }
    return false
}

inline fun <T> List<T>.forEachIndexed(action: (Int, T) -> Unit) {
    for (index in indices) {
        action(index, this[index])
    }
}

inline fun <T> List<T>.forEachReversedIndexed(action: (Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, this[index])
    }
}

inline fun <T> List<T>.noneIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun <T> MutableList<T>.removeAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <T> MutableList<T>.retainAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (!predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}
