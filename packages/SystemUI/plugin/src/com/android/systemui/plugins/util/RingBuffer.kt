/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.plugins.util

import kotlin.math.max

/**
 * A simple ring buffer implementation
 *
 * Use [advance] to get the least recent item in the buffer (and then presumably fill it with
 * appropriate data). This will cause it to become the most recent item.
 *
 * As the buffer is used, it will grow, allocating new instances of T using [factory] until it
 * reaches [maxSize]. After this point, no new instances will be created. Instead, the "oldest"
 * instances will be recycled from the back of the buffer and placed at the front.
 *
 * @param maxSize The maximum size the buffer can grow to before it begins functioning as a ring.
 * @param factory A function that creates a fresh instance of T. Used by the buffer while it's
 *   growing to [maxSize].
 */
class RingBuffer<T>(private val maxSize: Int, private val factory: () -> T) : Iterable<T> {

    private val buffer = MutableList<T?>(maxSize) { null }

    /**
     * An abstract representation that points to the "end" of the buffer. Increments every time
     * [advance] is called and never wraps. Use [indexOf] to calculate the associated index into the
     * backing array. Always points to the "next" available slot in the buffer. Before the buffer
     * has completely filled, the value pointed to will be null. Afterward, it will be the value at
     * the "beginning" of the buffer.
     *
     * This value is unlikely to overflow. Assuming [advance] is called at rate of 100 calls/ms,
     * omega will overflow after a little under three million years of continuous operation.
     */
    private var omega: Long = 0

    /**
     * The number of items currently stored in the buffer. Calls to [advance] will cause this value
     * to increase by one until it reaches [maxSize].
     */
    val size: Int
        get() = if (omega < maxSize) omega.toInt() else maxSize

    /**
     * Advances the buffer's position by one and returns the value that is now present at the "end"
     * of the buffer. If the buffer is not yet full, uses [factory] to create a new item. Otherwise,
     * reuses the value that was previously at the "beginning" of the buffer.
     *
     * IMPORTANT: The value is returned as-is, without being reset. It will retain any data that was
     * previously stored on it.
     */
    fun advance(): T {
        val index = indexOf(omega)
        omega += 1
        val entry = buffer[index] ?: factory().also { buffer[index] = it }
        return entry
    }

    /**
     * Returns the value stored at [index], which can range from 0 (the "start", or oldest element
     * of the buffer) to [size]
     * - 1 (the "end", or newest element of the buffer).
     */
    operator fun get(index: Int): T {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index $index is out of bounds")
        }

        // If omega is larger than the maxSize, then the buffer is full, and omega is equivalent
        // to the "start" of the buffer. If omega is smaller than the maxSize, then the buffer is
        // not yet full and our start should be 0. However, in modspace, maxSize and 0 are
        // equivalent, so we can get away with using it as the start value instead.
        val start = max(omega, maxSize.toLong())

        return buffer[indexOf(start + index)]!!
    }

    inline fun forEach(action: (T) -> Unit) {
        for (i in 0 until size) {
            action(get(i))
        }
    }

    override fun iterator(): Iterator<T> {
        return object : Iterator<T> {
            private var position: Int = 0

            override fun next(): T {
                if (position >= size) {
                    throw NoSuchElementException()
                }
                return get(position).also { position += 1 }
            }

            override fun hasNext(): Boolean {
                return position < size
            }
        }
    }

    private fun indexOf(position: Long): Int {
        return (position % maxSize).toInt()
    }
}
