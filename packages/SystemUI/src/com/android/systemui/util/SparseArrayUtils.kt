/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.util

import android.util.SparseArray

/**
 * Transforms a sequence of Key/Value pairs into a SparseArray.
 *
 * See [kotlin.collections.toMap].
 */
fun <T> Sequence<Pair<Int, T>>.toSparseArray(size: Int = -1): SparseArray<T> {
    val sparseArray = when {
        size < 0 -> SparseArray<T>()
        else -> SparseArray<T>(size)
    }
    for ((i, v) in this) {
        sparseArray.put(i, v)
    }
    return sparseArray
}

/**
 * Transforms an [Array] into a [SparseArray], by applying each element to [keySelector] in order to
 * generate the index at which it will be placed. If two elements produce the same index, the latter
 * replaces the former in the final result.
 *
 * See [Array.associateBy].
 */
inline fun <T> Array<T>.associateByToSparseArray(
    crossinline keySelector: (T) -> Int
): SparseArray<T> {
    val sparseArray = SparseArray<T>(size)
    for (value in this) {
        sparseArray.put(keySelector(value), value)
    }
    return sparseArray
}

/**
 * Folds a [Grouping] into a [SparseArray]. See [Grouping.fold].
 */
inline fun <T, R> Grouping<T, Int>.foldToSparseArray(
    initial: R,
    size: Int = -1,
    crossinline operation: (R, T) -> R
): SparseArray<R> {
    val sparseArray = when {
        size < 0 -> SparseArray<R>()
        else -> SparseArray<R>(size)
    }
    sourceIterator().forEach { elem ->
        val key = keyOf(elem)
        val acc = sparseArray.get(key) ?: initial
        sparseArray.put(key, operation(acc, elem))
    }
    return sparseArray
}

/**
 * Wraps this [SparseArray] into an immutable [Map], the methods of which forward to this
 * [SparseArray].
 */
fun <T> SparseArray<T>.asMap(): Map<Int, T> = SparseArrayMapWrapper(this)

private class SparseArrayMapWrapper<T>(
    private val sparseArray: SparseArray<T>
) : Map<Int, T> {

    private data class Entry<T>(override val key: Int, override val value: T) : Map.Entry<Int, T>

    private val entrySequence = sequence {
        val size = sparseArray.size()
        for (i in 0 until size) {
            val key = sparseArray.keyAt(i)
            val value = sparseArray.get(key)
            yield(Entry(key, value))
        }
    }

    override val entries: Set<Map.Entry<Int, T>>
        get() = object : Set<Map.Entry<Int, T>> {
            override val size: Int
                get() = this@SparseArrayMapWrapper.size

            override fun contains(element: Map.Entry<Int, T>): Boolean =
                    sparseArray[element.key]?.let { it == element.value } == true

            override fun containsAll(elements: Collection<Map.Entry<Int, T>>): Boolean =
                    elements.all { contains(it) }

            override fun isEmpty(): Boolean = size == 0

            override fun iterator(): Iterator<Map.Entry<Int, T>> = entrySequence.iterator()
        }

    override val keys: Set<Int> = object : Set<Int> {
        private val keySequence = entrySequence.map { it.key }

        override val size: Int
            get() = this@SparseArrayMapWrapper.size

        override fun contains(element: Int): Boolean = containsKey(element)

        override fun containsAll(elements: Collection<Int>): Boolean =
                elements.all { contains(it) }

        override fun isEmpty(): Boolean = size == 0

        override fun iterator(): Iterator<Int> = keySequence.iterator()
    }
    override val size: Int
        get() = sparseArray.size()
    override val values: Collection<T>
        get() = object : Collection<T> {
            private val valueSequence = entrySequence.map { it.value }

            override val size: Int
                get() = this@SparseArrayMapWrapper.size

            override fun contains(element: T): Boolean = containsValue(element)

            override fun containsAll(elements: Collection<T>): Boolean =
                    elements.all { contains(it) }

            override fun isEmpty(): Boolean = this@SparseArrayMapWrapper.isEmpty()

            override fun iterator(): Iterator<T> = valueSequence.iterator()
        }

    override fun containsKey(key: Int): Boolean = sparseArray.contains(key)

    override fun containsValue(value: T): Boolean = sparseArray.indexOfValue(value) >= 0

    override fun get(key: Int): T? = sparseArray.get(key)

    override fun isEmpty(): Boolean = sparseArray.size() == 0
}