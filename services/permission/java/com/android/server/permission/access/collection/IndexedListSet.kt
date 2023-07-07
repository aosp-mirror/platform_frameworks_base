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

class IndexedListSet<T> private constructor(
    private val list: ArrayList<T>
) : MutableSet<T> {
    constructor() : this(ArrayList())

    override val size: Int
        get() = list.size

    override fun contains(element: T): Boolean = list.contains(element)

    override fun isEmpty(): Boolean = list.isEmpty()

    override fun iterator(): MutableIterator<T> = list.iterator()

    override fun containsAll(elements: Collection<T>): Boolean {
        throw NotImplementedError()
    }

    fun elementAt(index: Int): T = list[index]

    fun indexOf(element: T): Int = list.indexOf(element)

    override fun add(element: T): Boolean =
        if (list.contains(element)) {
            false
        } else {
            list.add(element)
            true
        }

    override fun remove(element: T): Boolean = list.remove(element)

    override fun clear() {
        list.clear()
    }

    override fun addAll(elements: Collection<T>): Boolean {
        throw NotImplementedError()
    }

    override fun removeAll(elements: Collection<T>): Boolean {
        throw NotImplementedError()
    }

    override fun retainAll(elements: Collection<T>): Boolean {
        throw NotImplementedError()
    }

    fun removeAt(index: Int): T? = list.removeAt(index)

    fun copy(): IndexedListSet<T> = IndexedListSet(ArrayList(list))
}

inline fun <T> IndexedListSet<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (!predicate(index, element)) {
            return false
        }
    }
    return true
}

inline fun <T> IndexedListSet<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return true
        }
    }
    return false
}

inline fun <T> IndexedListSet<T>.forEachIndexed(action: (Int, T) -> Unit) {
    for (index in indices) {
        action(index, elementAt(index))
    }
}

inline fun <T> IndexedListSet<T>.forEachReversedIndexed(action: (Int, T) -> Unit) {
    for (index in lastIndex downTo 0) {
        action(index, elementAt(index))
    }
}

inline val <T> IndexedListSet<T>.lastIndex: Int
    get() = size - 1

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> IndexedListSet<T>.minus(element: T): IndexedListSet<T> =
    copy().apply { this -= element }

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> IndexedListSet<T>.minusAssign(element: T) {
    remove(element)
}

inline fun <T> IndexedListSet<T>.noneIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, element ->
        if (predicate(index, element)) {
            return false
        }
    }
    return true
}

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> IndexedListSet<T>.plus(element: T): IndexedListSet<T> =
    copy().apply { this += element }

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> IndexedListSet<T>.plusAssign(element: T) {
    add(element)
}

inline fun <T> IndexedListSet<T>.removeAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}

inline fun <T> IndexedListSet<T>.retainAllIndexed(predicate: (Int, T) -> Boolean): Boolean {
    var isChanged = false
    forEachReversedIndexed { index, element ->
        if (!predicate(index, element)) {
            removeAt(index)
            isChanged = true
        }
    }
    return isChanged
}
