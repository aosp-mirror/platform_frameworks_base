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

/** Immutable set with index-based access, implemented using a list. */
sealed class IndexedListSet<T>(internal val list: ArrayList<T>) :
    Immutable<MutableIndexedListSet<T>> {
    val size: Int
        get() = list.size

    fun isEmpty(): Boolean = list.isEmpty()

    operator fun contains(element: T): Boolean = list.contains(element)

    fun indexOf(element: T): Int = list.indexOf(element)

    @Suppress("ReplaceGetOrSet") fun elementAt(index: Int): T = list.get(index)

    override fun toMutable(): MutableIndexedListSet<T> = MutableIndexedListSet(this)

    override fun toString(): String = list.toString()
}

/** Mutable set with index-based access, implemented using a list. */
class MutableIndexedListSet<T>(list: ArrayList<T> = ArrayList()) : IndexedListSet<T>(list) {
    constructor(indexedListSet: IndexedListSet<T>) : this(ArrayList(indexedListSet.list))

    fun add(element: T): Boolean =
        if (list.contains(element)) {
            false
        } else {
            list.add(element)
            true
        }

    fun remove(element: T): Boolean = list.remove(element)

    fun clear() {
        list.clear()
    }

    fun removeAt(index: Int): T = list.removeAt(index)
}
