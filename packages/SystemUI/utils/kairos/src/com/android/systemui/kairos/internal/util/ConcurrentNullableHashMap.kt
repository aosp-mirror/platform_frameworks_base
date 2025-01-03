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

package com.android.systemui.kairos.internal.util

import com.android.systemui.kairos.internal.store.NoValue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

internal class ConcurrentNullableHashMap<K, V>
private constructor(private val inner: ConcurrentHashMap<Any, Any>) :
    ConcurrentMap<K, V>, AbstractMutableMap<K, V>() {

    constructor() : this(ConcurrentHashMap())

    constructor(capacity: Int) : this(ConcurrentHashMap(capacity))

    override fun get(key: K): V? = inner[key ?: NullValue]?.let { toNullable<V>(it) }

    fun getValue(key: K): V = toNullable(inner.getValue(key ?: NullValue))

    @Suppress("UNCHECKED_CAST")
    override fun put(key: K, value: V): V? =
        inner.put(key ?: NullValue, value ?: NullValue)?.takeIf { it !== NullValue } as V?

    operator fun set(key: K, value: V) {
        put(key, value)
    }

    fun toMap(): Map<K, V> =
        inner.asSequence().associate { (k, v) -> toNullable<K>(k) to toNullable(v) }

    override fun clear() {
        inner.clear()
    }

    override fun remove(key: K, value: V): Boolean = inner.remove(key ?: NoValue, value ?: NoValue)

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<K, V>>() {
            val wrapped = inner.entries

            override fun add(element: MutableMap.MutableEntry<K, V>): Boolean {
                val e =
                    object : MutableMap.MutableEntry<Any, Any> {
                        override val key: Any
                            get() = element.key ?: NullValue

                        override val value: Any
                            get() = element.value ?: NullValue

                        override fun setValue(newValue: Any): Any =
                            element.setValue(toNullable(newValue)) ?: NullValue
                    }
                return wrapped.add(e)
            }

            override val size: Int
                get() = wrapped.size

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<K, V>> {
                val iter = wrapped.iterator()
                return object : MutableIterator<MutableMap.MutableEntry<K, V>> {
                    override fun hasNext(): Boolean = iter.hasNext()

                    override fun next(): MutableMap.MutableEntry<K, V> {
                        val element = iter.next()
                        return object : MutableMap.MutableEntry<K, V> {
                            override val key: K
                                get() = toNullable(element.key)

                            override val value: V
                                get() = toNullable(element.value)

                            override fun setValue(newValue: V): V =
                                toNullable(element.setValue(newValue ?: NullValue))
                        }
                    }

                    override fun remove() {
                        iter.remove()
                    }
                }
            }
        }

    override fun replace(key: K, oldValue: V, newValue: V): Boolean =
        inner.replace(key ?: NullValue, oldValue ?: NullValue, newValue ?: NullValue)

    override fun replace(key: K, value: V): V? =
        inner.replace(key ?: NullValue, value ?: NullValue)?.let { toNullable<V>(it) }

    override fun putIfAbsent(key: K, value: V): V? =
        inner.putIfAbsent(key ?: NullValue, value ?: NullValue)?.let { toNullable<V>(it) }

    @Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
    private inline fun <T> toNullable(value: Any): T = value.takeIf { it !== NullValue } as T

    fun isNotEmpty(): Boolean = inner.isNotEmpty()

    @Suppress("UNCHECKED_CAST")
    override fun remove(key: K): V? =
        inner.remove(key ?: NullValue)?.takeIf { it !== NullValue } as V?

    fun asSequence(): Sequence<Pair<K, V>> =
        inner.asSequence().map { (key, value) -> toNullable<K>(key) to toNullable(value) }

    override fun isEmpty(): Boolean = inner.isEmpty()

    override fun containsKey(key: K): Boolean = inner.containsKey(key ?: NullValue)

    fun getOrPut(key: K, defaultValue: () -> V): V =
        toNullable(inner.getOrPut(key) { defaultValue() ?: NullValue })
}

private object NullValue
