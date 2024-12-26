/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.metadata

import java.util.function.Consumer

/**
 * A compact and immutable data structure provides [get] and for-each operations on ordered
 * key-value entries.
 *
 * The implementation uses fixed-size array and no API is offered to modify entries. Actually,
 * elements are provided in sorted order during constructor to simplify data management. As a
 * result, this class is more lightweight compared with `ArrayMap`.
 */
@Suppress("UNCHECKED_CAST")
class FixedArrayMap<K : Comparable<K>, V> {
    private val array: Array<Any?>

    /** Constructors with empty element. */
    constructor() {
        array = emptyArray()
    }

    /**
     * Constructors.
     *
     * @param size the number of elements
     * @param consumer initializer to provide exactly [size] elements *in sorted order*
     */
    constructor(size: Int, consumer: Consumer<OrderedInitializer<K, V>>) {
        array = arrayOfNulls(size * 2)
        val orderedInitializer = OrderedInitializer<K, V>(array)
        consumer.accept(orderedInitializer)
        orderedInitializer.verify()
    }

    /** Returns the number of elements. */
    val size: Int
        get() = array.size / 2

    /**
     * Returns a new [FixedArrayMap] that merged from current and given [FixedArrayMap] instance.
     *
     * [other] takes precedence for identical keys.
     */
    fun merge(other: FixedArrayMap<K, V>): FixedArrayMap<K, V> {
        var newKeys = 0
        other.forEachKey { if (get(it) == null) newKeys++ }
        return FixedArrayMap(size + newKeys) { initializer ->
            var index1 = 0
            var index2 = 0
            while (!initializer.isDone()) {
                val key1 = if (index1 < array.size) array[index1] as K else null
                val key2 = if (index2 < other.array.size) other.array[index2] as K else null
                val diff =
                    when {
                        key1 == null -> 1
                        key2 == null -> -1
                        else -> key1.compareTo(key2)
                    }
                if (diff < 0) {
                    initializer.put(key1!!, array[index1 + 1] as V)
                    index1 += 2
                } else {
                    initializer.put(key2!!, other.array[index2 + 1] as V)
                    index2 += 2
                    if (diff == 0) index1 += 2
                }
            }
        }
    }

    /** Traversals keys *in sorted order* and applies given action. */
    fun forEachKey(action: (key: K) -> Unit) {
        for (index in array.indices step 2) {
            action(array[index] as K)
        }
    }

    /** Traversals keys *in sorted order* and applies given action. */
    suspend fun forEachKeyAsync(action: suspend (key: K) -> Unit) {
        for (index in array.indices step 2) {
            action(array[index] as K)
        }
    }

    /** Traversals key-value entries *in sorted order* and applies given action. */
    fun forEach(action: (key: K, value: V) -> Unit) {
        for (index in array.indices step 2) {
            action(array[index] as K, array[index + 1] as V)
        }
    }

    /** Traversals key-value entries in sorted order and applies given action. */
    suspend fun forEachAsync(action: suspend (key: K, value: V) -> Unit) {
        for (index in array.indices step 2) {
            action(array[index] as K, array[index + 1] as V)
        }
    }

    /**
     * Returns the value associated with given key.
     *
     * Binary-search algorithm is applied, so this operation takes O(log2(N)) at worst case.
     */
    operator fun get(key: K): V? {
        var low = 0
        var high = array.size / 2
        while (low < high) {
            val mid = (low + high).ushr(1) // safe from overflows
            val diff = (array[mid * 2] as K).compareTo(key)
            when {
                diff < 0 -> low = mid + 1
                diff > 0 -> high = mid
                else -> return array[mid * 2 + 1] as V
            }
        }
        return null
    }

    /** Initializer to provide key-value pairs *in sorted order*. */
    class OrderedInitializer<K : Comparable<K>, V>
    internal constructor(private val array: Array<Any?>) {
        private var index = 0

        internal val size: Int
            get() = array.size

        /** Returns whether all elements are added. */
        fun isDone() = index == array.size

        /** Adds a new key-value entry. The key must be provided in sorted order. */
        fun put(key: K, value: V) {
            array[index++] = key
            array[index++] = value
        }

        internal fun verify() {
            if (!isDone()) throw IllegalStateException("Missing items: ${index / 2} / ${size / 2}")
            for (index in 2 until size step 2) {
                if ((array[index - 2] as K) >= (array[index] as K)) {
                    throw IllegalStateException("${array[index - 2]} >= ${array[index]}")
                }
            }
        }
    }
}
