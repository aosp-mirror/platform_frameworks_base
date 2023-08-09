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

package com.android.systemui.util

/**
 * A collection of listeners, observers, callbacks, etc.
 *
 * This container is optimized for infrequent mutation and frequent iteration, with thread safety
 * and reentrant-safety guarantees as well. Specifically, to ensure that
 * [ConcurrentModificationException] is never thrown, this iterator will not reflect changes made to
 * the set after the iterator is constructed.
 */
interface IListenerSet<E : Any> : Set<E> {
    /**
     * A thread-safe, reentrant-safe method to add a listener. Does nothing if the listener is
     * already in the set.
     */
    fun addIfAbsent(element: E): Boolean

    /** A thread-safe, reentrant-safe method to remove a listener. */
    fun remove(element: E): Boolean
}
