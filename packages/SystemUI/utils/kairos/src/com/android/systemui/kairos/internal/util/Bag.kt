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

internal class Bag<T> private constructor(private val intMap: MutableMap<T, Int>) :
    Set<T> by intMap.keys {

    constructor() : this(hashMapOf())

    override fun toString(): String = intMap.toString()

    fun add(element: T): Boolean {
        val entry = intMap[element]
        return if (entry != null) {
            intMap[element] = entry + 1
            false
        } else {
            intMap[element] = 1
            true
        }
    }

    fun remove(element: T): Boolean {
        val entry = intMap[element]
        return when {
            entry == null -> {
                false
            }
            entry <= 1 -> {
                intMap.remove(element)
                true
            }
            else -> {
                intMap[element] = entry - 1
                false
            }
        }
    }

    fun addAll(elements: Iterable<T>, butNot: T? = null): Set<T>? {
        val newlyAdded = hashSetOf<T>()
        for (value in elements) {
            if (value != butNot) {
                if (add(value)) {
                    newlyAdded.add(value)
                }
            }
        }
        return newlyAdded.ifEmpty { null }
    }

    fun clear() {
        intMap.clear()
    }

    fun removeAll(elements: Collection<T>): Set<T>? {
        val result = hashSetOf<T>()
        for (element in elements) {
            if (remove(element)) {
                result.add(element)
            }
        }
        return result.ifEmpty { null }
    }
}
