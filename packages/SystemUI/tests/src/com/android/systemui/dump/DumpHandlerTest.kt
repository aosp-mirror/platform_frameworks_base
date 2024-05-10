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
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.ProtoDumpable
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.FileDescriptor
import java.io.PrintWriter
import java.io.StringWriter
import javax.inject.Provider

@SmallTest
class DumpHandlerTest : SysuiTestCase() {

    private lateinit var dumpHandler: DumpHandler

    @Mock
    private lateinit var logBufferEulogizer: LogBufferEulogizer

    @Mock
    private lateinit var pw: PrintWriter
    @Mock
    private lateinit var fd: FileDescriptor

    @Mock
    private lateinit var dumpable1: Dumpable
    @Mock
    private lateinit var dumpable2: Dumpable
    @Mock
    private lateinit var dumpable3: Dumpable

    @Mock
    private lateinit var protoDumpable1: ProtoDumpable
    @Mock
    private lateinit var protoDumpable2: ProtoDumpable

    @Mock
    private lateinit var buffer1: LogBuffer
    @Mock
    private lateinit var buffer2: LogBuffer

    @Mock
    private lateinit var table1: TableLogBuffer
    @Mock
    private lateinit var table2: TableLogBuffer

    private val dumpManager = DumpManager()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        val config = SystemUIConfigDumpable(
                dumpManager,
                mContext,
                mutableMapOf(EmptyCoreStartable::class.java to Provider { EmptyCoreStartable() }),
        )
        dumpHandler = DumpHandler(dumpManager, logBufferEulogizer, config)
    }

    @Test
    fun testDumpablesCanBeDumpedSelectively() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerCriticalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN some of them are dumped explicitly
        val args = arrayOf("dumpable1", "dumpable3", "buffer2", "table2")
        dumpHandler.dump(fd, pw, args)

        // THEN only the requested ones have their dump() method called
        verify(dumpable1).dump(pw, args)
        verify(dumpable2, never()).dump(
            any(PrintWriter::class.java),
            any(Array<String>::class.java))
        verify(dumpable3).dump(pw, args)
        verify(buffer1, never()).dump(any(PrintWriter::class.java), anyInt())
        verify(buffer2).dump(pw, 0)
        verify(table1, never()).dump(any(), any())
        verify(table2).dump(pw, args)
    }

    @Test
    fun testDumpableMatchingIsBasedOnEndOfTag() {
        // GIVEN a dumpable registered to the manager
        dumpManager.registerCriticalDumpable("com.android.foo.bar.dumpable1", dumpable1)

        // WHEN that module is dumped
        val args = arrayOf("dumpable1")
        dumpHandler.dump(fd, pw, args)

        // THEN its dump() method is called
        verify(dumpable1).dump(pw, args)
    }

    @Test
    fun testCriticalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN a critical dump is requested
        val args = arrayOf("--dump-priority", "CRITICAL")
        dumpHandler.dump(fd, pw, args)

        // THEN only critical modules are dumped (and no buffers)
        verify(dumpable1).dump(pw, args)
        verify(dumpable2).dump(pw, args)
        verify(dumpable3, never()).dump(
            any(PrintWriter::class.java),
            any(Array<String>::class.java))
        verify(buffer1, never()).dump(any(PrintWriter::class.java), anyInt())
        verify(buffer2, never()).dump(any(PrintWriter::class.java), anyInt())
        verify(table1, never()).dump(any(), any())
        verify(table2, never()).dump(any(), any())
    }

    @Test
    fun testNormalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN a normal dump is requested
        val args = arrayOf("--dump-priority", "NORMAL")
        dumpHandler.dump(fd, pw, args)

        // THEN the normal module and all buffers are dumped
        verify(dumpable1, never()).dump(
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(dumpable2, never()).dump(
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(dumpable3).dump(pw, args)
        verify(buffer1).dump(pw, 0)
        verify(buffer2).dump(pw, 0)
        verify(table1).dump(pw, args)
        verify(table2).dump(pw, args)
    }

    @Test
    fun testConfigDump() {
        // GIVEN a StringPrintWriter
        val stringWriter = StringWriter()
        val spw = PrintWriter(stringWriter)

        // When a config dump is requested
        dumpHandler.dump(fd, spw, arrayOf("config"))

        assertThat(stringWriter.toString()).contains(EmptyCoreStartable::class.java.simpleName)
    }

    @Test
    fun testDumpBuffers() {
        // GIVEN a variety of registered dumpables and buffers and tables
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN a buffer dump is requested
        val args = arrayOf("buffers", "--tail", "1")
        dumpHandler.dump(fd, pw, args)

        // THEN all buffers are dumped (and no dumpables or tables)
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1).dump(pw, tailLength = 1)
        verify(buffer2).dump(pw, tailLength = 1)
        verify(table1, never()).dump(any(), any())
        verify(table2, never()).dump(any(), any())
    }

    @Test
    fun testDumpDumpables() {
        // GIVEN a variety of registered dumpables and buffers and tables
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN a dumpable dump is requested
        val args = arrayOf("dumpables")
        dumpHandler.dump(fd, pw, args)

        // THEN all dumpables are dumped (both critical and normal) (and no dumpables)
        verify(dumpable1).dump(pw, args)
        verify(dumpable2).dump(pw, args)
        verify(dumpable3).dump(pw, args)
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
        verify(table1, never()).dump(any(), any())
        verify(table2, never()).dump(any(), any())
    }

    @Test
    fun testDumpTables() {
        // GIVEN a variety of registered dumpables and buffers and tables
        dumpManager.registerCriticalDumpable("dumpable1", dumpable1)
        dumpManager.registerCriticalDumpable("dumpable2", dumpable2)
        dumpManager.registerNormalDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)
        dumpManager.registerTableLogBuffer("table1", table1)
        dumpManager.registerTableLogBuffer("table2", table2)

        // WHEN a dumpable dump is requested
        val args = arrayOf("tables")
        dumpHandler.dump(fd, pw, args)

        // THEN all dumpables are dumped (both critical and normal) (and no dumpables)
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1, never()).dump(any(), anyInt())
        verify(buffer2, never()).dump(any(), anyInt())
        verify(table1).dump(pw, args)
        verify(table2).dump(pw, args)
    }

    @Test
    fun testDumpAllProtoDumpables() {
        dumpManager.registerDumpable("protoDumpable1", protoDumpable1)
        dumpManager.registerDumpable("protoDumpable2", protoDumpable2)

        val args = arrayOf(DumpHandler.PROTO)
        dumpHandler.dump(fd, pw, args)

        verify(protoDumpable1).dumpProto(any(), eq(args))
        verify(protoDumpable2).dumpProto(any(), eq(args))
    }

    @Test
    fun testDumpSingleProtoDumpable() {
        dumpManager.registerDumpable("protoDumpable1", protoDumpable1)
        dumpManager.registerDumpable("protoDumpable2", protoDumpable2)

        val args = arrayOf(DumpHandler.PROTO, "protoDumpable1")
        dumpHandler.dump(fd, pw, args)

        verify(protoDumpable1).dumpProto(any(), eq(args))
        verify(protoDumpable2, never()).dumpProto(any(), any())
    }

    @Test
    fun testDumpTarget_selectsShortestNamedDumpable() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerCriticalDumpable("first-dumpable", dumpable1)
        dumpManager.registerCriticalDumpable("scnd-dumpable", dumpable2)
        dumpManager.registerCriticalDumpable("third-dumpable", dumpable3)

        // WHEN a dumpable is dumped by a suffix that matches multiple options
        val args = arrayOf("dumpable")
        dumpHandler.dump(fd, pw, args)

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
        val args = arrayOf("buffer", "--tail", "14")
        dumpHandler.dump(fd, pw, args)

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
        val args = arrayOf("2")
        dumpHandler.dump(fd, pw, args)

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
        val args = arrayOf("2", "--tail", "14")
        dumpHandler.dump(fd, pw, args)

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
        val args = arrayOf("x")
        dumpHandler.dump(fd, pw, args)

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
        val args = arrayOf("x", "--tail", "14")
        dumpHandler.dump(fd, pw, args)

        // THEN the alphabetically first dumpable/buffer (of the 3 letter names) is dumped
        verify(dumpable1, never()).dump(any(), any())
        verify(dumpable2, never()).dump(any(), any())
        verify(dumpable3, never()).dump(any(), any())
        verify(buffer1).dump(pw, tailLength = 14)
        verify(buffer2, never()).dump(any(), anyInt())
    }


    private class EmptyCoreStartable : CoreStartable {
        override fun start() {}
    }
}
