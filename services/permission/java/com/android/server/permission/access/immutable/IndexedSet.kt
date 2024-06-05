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

import android.util.ArraySet

/** Immutable set with index-based access. */
sealed class IndexedSet<T>(internal val set: ArraySet<T>) : Immutable<MutableIndexedSet<T>> {
    val size: Int
        get() = set.size

    fun isEmpty(): Boolean = set.isEmpty()

    operator fun contains(element: T): Boolean = set.contains(element)

    fun indexOf(element: T): Int = set.indexOf(element)

    fun elementAt(index: Int): T = set.elementAt(index)

    override fun toMutable(): MutableIndexedSet<T> = MutableIndexedSet(this)

    override fun toString(): String = set.toString()
}

/** Mutable set with index-based access. */
class MutableIndexedSet<T>(set: ArraySet<T> = ArraySet()) : IndexedSet<T>(set) {
    constructor(indexedSet: IndexedSet<T>) : this(ArraySet(indexedSet.set))

    fun add(element: T): Boolean = set.add(element)

    fun remove(element: T): Boolean = set.remove(element)

    fun clear() {
        set.clear()
    }

    fun removeAt(index: Int): T = set.removeAt(index)
}
