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

package com.android.systemui.util

/**
 * A collection of listeners, observers, callbacks, etc.
 *
 * This container is optimized for frequent mutation and infrequent iteration, with reentrant-safety
 * guarantees but without thread-safety guarantees. Specifically, to ensure that
 * [ConcurrentModificationException] is not thrown when listeners mutate the set, this iterator will
 * not reflect changes made to the set after the iterator is constructed.
 */
class CopyOnLoopListenerSet<E : Any>
/** Private constructor takes the internal list so that we can use auto-delegation */
private constructor(private val listeners: ArrayList<E>) :
    Collection<E> by listeners, IListenerSet<E> {

    /** Create a new instance */
    constructor() : this(ArrayList())

    @Suppress("UNCHECKED_CAST")
    override fun iterator(): Iterator<E> = listeners.toArray().iterator() as Iterator<E>

    override fun addIfAbsent(element: E): Boolean =
        if (element in listeners) {
            false
        } else {
            listeners.add(element)
        }

    override fun remove(element: E): Boolean = listeners.remove(element)
}
