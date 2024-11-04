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

import java.util.concurrent.ConcurrentHashMap

internal class ConcurrentNullableHashMap<K : Any, V>
private constructor(private val inner: ConcurrentHashMap<K, Any>) {
    constructor() : this(ConcurrentHashMap())

    @Suppress("UNCHECKED_CAST")
    operator fun get(key: K): V? = inner[key]?.takeIf { it !== NullValue } as V?

    @Suppress("UNCHECKED_CAST")
    fun put(key: K, value: V?): V? =
        inner.put(key, value ?: NullValue)?.takeIf { it !== NullValue } as V?

    operator fun set(key: K, value: V?) {
        put(key, value)
    }

    @Suppress("UNCHECKED_CAST")
    fun toMap(): Map<K, V> = inner.mapValues { (_, v) -> v.takeIf { it !== NullValue } as V }

    fun clear() {
        inner.clear()
    }

    fun isNotEmpty(): Boolean = inner.isNotEmpty()
}

private object NullValue
