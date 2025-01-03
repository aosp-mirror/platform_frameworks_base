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

/**
 * Higher-kinded encoding for [Map].
 *
 * Let's say you want to write a class that is generic over both a map, and the type of data within
 * the map:
 * ``` kotlin
 *   class Foo<TMap, TKey, TValue> {
 *     val container: TMap<TKey, TElement> // disallowed!
 *   }
 * ```
 *
 * You can use `MapK` to represent the "higher-kinded" type variable `TMap`:
 * ``` kotlin
 *   class Foo<TMap, TKey, TValue> {
 *      val container: MapK<TMap, TKey, TValue> // OK!
 *   }
 * ```
 *
 * Note that Kotlin will not let you use the generic type without parameters as `TMap`:
 * ``` kotlin
 *   val fooHk: MapK<HashMap, Int, String> // not allowed: HashMap requires two type parameters
 * ```
 *
 * To work around this, you need to declare a special type-witness object. This object is only used
 * at compile time and can be stripped out by a minifier because it's never used at runtime.
 *
 * ``` kotlin
 *   class Foo<A, B> : MapK<FooWitness, A, B> { ... }
 *   object FooWitness
 *
 *   // safe, as long as Foo is the only implementor of MapK<FooWitness, *, *>
 *   fun <A, B> MapK<FooWitness, A, B>.asFoo(): Foo<A, B> = this as Foo<A, B>
 *
 *   val fooStore: MapK<FooWitness, Int, String> = Foo()
 *   val foo: Foo<Int, String> = fooStore.asFoo()
 * ```
 */
internal interface MapK<W, K, V> : Map<K, V>

internal interface MutableMapK<W, K, V> : MutableMap<K, V> {

    fun readOnlyCopy(): MapK<W, K, V>

    fun asReadOnly(): MapK<W, K, V>

    interface Factory<W, K> {
        fun <V> create(capacity: Int?): MutableMapK<W, K, V>

        fun <V> create(input: MapK<W, K, V>): MutableMapK<W, K, V>
    }
}

internal object NoValue

internal data class StoreEntry<K, V>(override var key: K, override var value: V) :
    MutableMap.MutableEntry<K, V> {
    override fun setValue(newValue: V): V = value.also { value = newValue }
}
