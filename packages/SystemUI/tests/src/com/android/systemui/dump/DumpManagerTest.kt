/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.dump

import androidx.test.filters.SmallTest
import com.android.systemui.Dumpable
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.util.mockito.any
import java.io.PrintWriter
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
class DumpManagerTest : SysuiTestCase() {

    @Mock private lateinit var pw: PrintWriter

    @Mock private lateinit var dumpable1: Dumpable
    @Mock private lateinit var dumpable2: Dumpable
    @Mock private lateinit var dumpable3: Dumpable

    @Mock private lateinit var buffer1: LogBuffer
    @Mock private lateinit var buffer2: LogBuffer

    private val dumpManager = DumpManager()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testDumpTarget_dumpable() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerCriticalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a dumpable is dumped explicitly
        val args = arrayOf<String>()
        dumpManager.dumpTarget("dumpable2", pw, args, tailLength = 0)

        // THEN only the requested one has their dump() method called
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2).dump(pw, args)
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpTarget_buffer() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerCriticalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a buffer is dumped explicitly
        val args = arrayOf<String>()
        dumpManager.dumpTarget("buffer1", pw, args, tailLength = 14)

        // THEN only the requested one has their dump() method called
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1).dump(pw, tailLength = 14)
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpableMatchingIsBasedOnEndOfTag() {
        // GIVEN a dumpable registered to the manager
        dumpManager.registerCriticalDumpable("com.android.foo.bar.dumpable1", dumpable1)

        // WHEN that module is dumped
        val args = arrayOf<String>()
        dumpManager.dumpTarget("dumpable1", pw, arrayOf(), tailLength = 14)

        // THEN its dump() method is called
        verify(dumpable1).dump(pw, args)
    }

    @Test
    fun testDumpTarget_selectsShortestNamedDumpable() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("first-dumpable", dumpable1)
        dumpManager.registerCriticalDumpable("scnd-dumpable", dumpable2)
        dumpManager.registerCriticalDumpable("third-dumpable", dumpable3)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("dumpable", pw, args, tailLength = 0)

        // THEN the matching dumpable with the shorter name is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2).dump(pw, args)
        verify(dumpable3, never()).dump(any(), any())
    }

    @Test
    fun testDumpTarget_selectsShortestNamedBuffer() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerBuffer("first-buffer", buffer1)
        dumpManager.registerBuffer("scnd-buffer", buffer2)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("buffer", pw, args, tailLength = 14)

        // THEN the matching buffer with the shorter name is dumped
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2).dump(pw, tailLength = 14)
    }

    @Test
    fun testDumpTarget_selectsShortestNamedMatch_dumpable() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerCriticalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("big-buffer1", buffer1)
        dumpManager.registerBuffer("big-buffer2", buffer2)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("2", pw, args, tailLength = 14)

        // THEN the matching dumpable with the shorter name is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2).dump(pw, args)
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpTarget_selectsShortestNamedMatch_buffer() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerCriticalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("2", pw, args, tailLength = 14)

        // THEN the matching buffer with the shorter name is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2).dump(pw, tailLength = 14)
    }

    @Test
    fun testDumpTarget_selectsTheAlphabeticallyFirstShortestMatch_dumpable() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("d1x", dumpable1)
        dumpManager.registerCriticalDumpable("d2x", dumpable2)
        dumpManager.registerCriticalDumpable("a3x", dumpable3)
        dumpManager.registerBuffer("ab1x", buffer1)
        dumpManager.registerBuffer("b2x", buffer2)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("x", pw, args, tailLength = 14)

        // THEN the alphabetically first dumpable/buffer (of the 3 letter names) is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3).dump(pw, args)
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpTarget_selectsTheAlphabeticallyFirstShortestMatch_buffer() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("d1x", dumpable1)
        dumpManager.registerCriticalDumpable("d2x", dumpable2)
        dumpManager.registerCriticalDumpable("az1x", dumpable3)
        dumpManager.registerBuffer("b1x", buffer1)
        dumpManager.registerBuffer("b2x", buffer2)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf<String>()
        dumpManager.dumpTarget("x", pw, args, tailLength = 14)

        // THEN the alphabetically first dumpable/buffer (of the 3 letter names) is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1).dump(pw, tailLength = 14)
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpDumpables() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a dumpable dump is requested
        val args = arrayOf<String>()
        dumpManager.dumpDumpables(pw, args)

        // THEN all dumpables are dumped (both critical and normal) (and no dumpables)
        verify(dumpable1).dump(pw, args)
        verify(dumpable2).dump(pw, args)
        verify(dumpable3).dump(pw, args)
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testDumpBuffers() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a buffer dump is requested
        dumpManager.dumpBuffers(pw, tailLength = 1)

        // THEN all buffers are dumped (and no dumpables)
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1).dump(pw, tailLength = 1)
        verify(buffer2).dump(pw, tailLength = 1)
    }

    @Test
    fun testCriticalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a critical dump is requested
        val args = arrayOf<String>()
        dumpManager.dumpCritical(pw, args)

        // THEN only critical modules are dumped (and no buffers)
        verify(dumpable1).dump(pw, args)
        verify(dumpable2).dump(pw, args)
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
    }

    @Test
    fun testNormalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a normal dump is requested
        val args = arrayOf<String>()
        dumpManager.dumpNormal(pw, args, tailLength = 2)

        // THEN the normal module and all buffers are dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3).dump(pw, args)
        verify(buffer1).dump(pw, tailLength = 2)
        verify(buffer2).dump(pw, tailLength = 2)
    }

    @Test
    fun testUnregister() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)

        dumpManager.unregisterDumpable("dumpable2")
        dumpManager.unregisterDumpable("dumpable3")

        // WHEN a dumpables dump is requested
        val args = arrayOf<String>()
        dumpManager.dumpDumpables(pw, args)

        // THEN the unregistered dumpables (both normal and critical) are not dumped
        verify(dumpable1).dump(pw, args)
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
    }
}
