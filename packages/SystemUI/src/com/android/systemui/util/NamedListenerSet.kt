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

import com.android.app.tracing.traceSection
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * A collection of listeners, observers, callbacks, etc.
 *
 * This container is optimized for infrequent mutation and frequent iteration, with thread safety
 * and reentrant-safety guarantees as well. Specifically, to ensure that
 * [ConcurrentModificationException] is never thrown, this iterator will not reflect changes made to
 * the set after the iterator is constructed.
 *
 * This class provides all the abilities of [ListenerSet], except that each listener has a name
 * calculated at runtime which can be used for time-efficient tracing of listener invocations.
 */
class NamedListenerSet<E : Any>(
    private val getName: (E) -> String = { it.javaClass.name },
) : IListenerSet<E> {
    private val listeners = CopyOnWriteArrayList<NamedListener>()

    override val size: Int
        get() = listeners.size

    override fun isEmpty() = listeners.isEmpty()

    override fun iterator(): Iterator<E> = iterator {
        listeners.iterator().forEach { yield(it.listener) }
    }

    override fun containsAll(elements: Collection<E>) =
        listeners.count { it.listener in elements } == elements.size

    override fun contains(element: E) = listeners.firstOrNull { it.listener == element } != null

    override fun addIfAbsent(element: E): Boolean = listeners.addIfAbsent(NamedListener(element))

    override fun remove(element: E): Boolean = listeners.removeIf { it.listener == element }

    /** A wrapper for the listener with an associated name. */
    inner class NamedListener(val listener: E) {
        val name: String = getName(listener)

        override fun hashCode(): Int {
            return listener.hashCode()
        }

        override fun equals(other: Any?): Boolean =
            when {
                other === null -> false
                other === this -> true
                other !is NamedListenerSet<*>.NamedListener -> false
                listener == other.listener -> true
                else -> false
            }
    }

    /** Iterate the listeners in the set, providing the name for each one as well. */
    inline fun forEachNamed(block: (String, E) -> Unit) =
        namedIterator().forEach { element -> block(element.name, element.listener) }

    /**
     * Iterate the listeners in the set, wrapping each call to the block with [traceSection] using
     * the listener name.
     */
    inline fun forEachTraced(block: (E) -> Unit) = forEachNamed { name, listener ->
        traceSection(name) { block(listener) }
    }

    /**
     * Iterate the listeners in the set, wrapping each call to the block with [traceSection] using
     * the listener name.
     */
    fun forEachTraced(consumer: Consumer<E>) = forEachNamed { name, listener ->
        traceSection(name) { consumer.accept(listener) }
    }

    /** Iterate over the [NamedListener]s currently in the set. */
    fun namedIterator(): Iterator<NamedListener> = listeners.iterator()
}
