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

package com.android.systemui.kairos.internal.store

@Suppress("NOTHING_TO_INLINE") internal inline fun <V> singleOf(value: V) = Single<V>(value)

/** A [Map] with a single element that has key [Unit]. */
internal class Single<V>(val unwrapped: Any?) : MapK<Single.W, Unit, V>, AbstractMap<Unit, V>() {

    constructor() : this(NoValue)

    @Suppress("UNCHECKED_CAST")
    override val entries: Set<Map.Entry<Unit, V>> =
        if (unwrapped === NoValue) emptySet() else setOf(StoreEntry(Unit, unwrapped as V))

    object W
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun <V> MapK<Single.W, Unit, V>.asSingle(): Single<V> = this as Single<V>

internal class SingletonMapK<V>(@Volatile private var value: Any?) :
    MutableMapK<Single.W, Unit, V>, AbstractMutableMap<Unit, V>() {

    constructor() : this(NoValue)

    override fun readOnlyCopy() =
        Single<V>(if (value === NoValue) value else (value as MutableMap.MutableEntry<*, *>).value)

    override fun asReadOnly(): MapK<Single.W, Unit, V> = readOnlyCopy()

    @Suppress("UNCHECKED_CAST")
    override fun put(key: Unit, value: V): V? =
        (this.value as? MutableMap.MutableEntry<Unit, V>)?.value.also {
            this.value = StoreEntry(Unit, value)
        }

    override val entries: MutableSet<MutableMap.MutableEntry<Unit, V>> =
        object : AbstractMutableSet<MutableMap.MutableEntry<Unit, V>>() {
            override fun add(element: MutableMap.MutableEntry<Unit, V>): Boolean =
                (value !== NoValue).also { value = element }

            override val size: Int
                get() = if (value === NoValue) 0 else 1

            override fun iterator(): MutableIterator<MutableMap.MutableEntry<Unit, V>> {
                return object : MutableIterator<MutableMap.MutableEntry<Unit, V>> {

                    var done = false

                    override fun hasNext(): Boolean = value !== NoValue && !done

                    override fun next(): MutableMap.MutableEntry<Unit, V> {
                        if (!hasNext()) throw NoSuchElementException()
                        done = true
                        @Suppress("UNCHECKED_CAST")
                        return value as MutableMap.MutableEntry<Unit, V>
                    }

                    override fun remove() {
                        if (!done || value === NoValue) throw IllegalStateException()
                        value = NoValue
                    }
                }
            }
        }

    internal class Factory : MutableMapK.Factory<Single.W, Unit> {
        override fun <V> create(capacity: Int?): SingletonMapK<V> {
            check(capacity == null || capacity == 0 || capacity == 1) {
                "Can't use singleton store with capacity > 1. Got: $capacity"
            }
            return SingletonMapK()
        }

        override fun <V> create(input: MapK<Single.W, Unit, V>): SingletonMapK<V> =
            SingletonMapK(input.asSingle().unwrapped)
    }
}
