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

package com.android.systemui.util.collection

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class RingBufferTest : SysuiTestCase() {

    private val buffer = RingBuffer(5) { TestElement() }

    private val history = mutableListOf<TestElement>()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testBarelyFillBuffer() {
        fillBuffer(5)

        assertEquals(0, buffer[0].id)
        assertEquals(1, buffer[1].id)
        assertEquals(2, buffer[2].id)
        assertEquals(3, buffer[3].id)
        assertEquals(4, buffer[4].id)
    }

    @Test
    fun testPartiallyFillBuffer() {
        fillBuffer(3)

        assertEquals(3, buffer.size)

        assertEquals(0, buffer[0].id)
        assertEquals(1, buffer[1].id)
        assertEquals(2, buffer[2].id)

        assertThrows(IndexOutOfBoundsException::class.java) { buffer[3] }
        assertThrows(IndexOutOfBoundsException::class.java) { buffer[4] }
    }

    @Test
    fun testSpinBuffer() {
        fillBuffer(277)

        assertEquals(272, buffer[0].id)
        assertEquals(273, buffer[1].id)
        assertEquals(274, buffer[2].id)
        assertEquals(275, buffer[3].id)
        assertEquals(276, buffer[4].id)
        assertThrows(IndexOutOfBoundsException::class.java) { buffer[5] }

        assertEquals(5, buffer.size)
    }

    @Test
    fun testElementsAreRecycled() {
        fillBuffer(23)

        assertSame(history[4], buffer[1])
        assertSame(history[9], buffer[1])
        assertSame(history[14], buffer[1])
        assertSame(history[19], buffer[1])
    }

    @Test
    fun testIterator() {
        fillBuffer(13)

        val iterator = buffer.iterator()

        for (i in 0 until 5) {
            assertEquals(history[8 + i], iterator.next())
        }
        assertFalse(iterator.hasNext())
        assertThrows(NoSuchElementException::class.java) { iterator.next() }
    }

    @Test
    fun testForEach() {
        fillBuffer(13)
        var i = 8

        buffer.forEach {
            assertEquals(history[i], it)
            i++
        }
        assertEquals(13, i)
    }

    private fun fillBuffer(count: Int) {
        for (i in 0 until count) {
            val elem = buffer.advance()
            elem.id = history.size
            history.add(elem)
        }
    }
}

private class TestElement(var id: Int = 0) {
    override fun toString(): String {
        return "{TestElement $id}"
    }
}