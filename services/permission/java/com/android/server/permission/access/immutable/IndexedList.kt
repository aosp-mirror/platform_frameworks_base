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

/** Immutable list with index-based access. */
sealed class IndexedList<T>(internal val list: ArrayList<T>) : Immutable<MutableIndexedList<T>> {
    val size: Int
        get() = list.size

    fun isEmpty(): Boolean = list.isEmpty()

    operator fun contains(element: T): Boolean = list.contains(element)

    @Suppress("ReplaceGetOrSet") operator fun get(index: Int): T = list.get(index)

    override fun toMutable(): MutableIndexedList<T> = MutableIndexedList(this)

    override fun toString(): String = list.toString()
}

/** Mutable list with index-based access. */
class MutableIndexedList<T>(list: ArrayList<T> = ArrayList()) : IndexedList<T>(list) {
    constructor(indexedList: IndexedList<T>) : this(ArrayList(indexedList.list))

    @Suppress("ReplaceGetOrSet")
    operator fun set(index: Int, element: T): T = list.set(index, element)

    fun add(element: T) {
        list.add(element)
    }

    fun add(index: Int, element: T) {
        list.add(index, element)
    }

    fun remove(element: T) {
        list.remove(element)
    }

    fun clear() {
        list.clear()
    }

    fun removeAt(index: Int): T = list.removeAt(index)
}
