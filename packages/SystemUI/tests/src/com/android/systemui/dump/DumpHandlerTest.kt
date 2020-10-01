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
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.FileDescriptor
import java.io.PrintWriter

@SmallTest
class DumpHandlerTest : SysuiTestCase() {

    private lateinit var dumpHandler: DumpHandler

    @Mock
    private lateinit var logBufferEulogizer: LogBufferEulogizer

    @Mock
    private lateinit var fd: FileDescriptor
    @Mock
    private lateinit var pw: PrintWriter

    @Mock
    private lateinit var dumpable1: Dumpable
    @Mock
    private lateinit var dumpable2: Dumpable
    @Mock
    private lateinit var dumpable3: Dumpable

    @Mock
    private lateinit var buffer1: LogBuffer
    @Mock
    private lateinit var buffer2: LogBuffer

    private val dumpManager = DumpManager()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        dumpHandler = DumpHandler(mContext, dumpManager, logBufferEulogizer)
    }

    @Test
    fun testDumpablesCanBeDumpedSelectively() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerDumpable("dumpable1", dumpable1)
        dumpManager.registerDumpable("dumpable2", dumpable2)
        dumpManager.registerDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN some of them are dumped explicitly
        val args = arrayOf("dumpable1", "dumpable3", "buffer2")
        dumpHandler.dump(fd, pw, args)

        // THEN only the requested ones have their dump() method called
        verify(dumpable1).dump(fd, pw, args)
        verify(dumpable2, never()).dump(
                any(FileDescriptor::class.java),
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(dumpable3).dump(fd, pw, args)
        verify(buffer1, never()).dump(any(PrintWriter::class.java), anyInt())
        verify(buffer2).dump(pw, 0)
    }

    @Test
    fun testDumpableMatchingIsBasedOnEndOfTag() {
        // GIVEN a dumpable registered to the manager
        dumpManager.registerDumpable("com.android.foo.bar.dumpable1", dumpable1)

        // WHEN that module is dumped
        val args = arrayOf("dumpable1")
        dumpHandler.dump(fd, pw, args)

        // THEN its dump() method is called
        verify(dumpable1).dump(fd, pw, args)
    }

    @Test
    fun testCriticalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerDumpable("dumpable1", dumpable1)
        dumpManager.registerDumpable("dumpable2", dumpable2)
        dumpManager.registerDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a critical dump is requested
        val args = arrayOf("--dump-priority", "CRITICAL")
        dumpHandler.dump(fd, pw, args)

        // THEN all modules are dumped (but no buffers)
        verify(dumpable1).dump(fd, pw, args)
        verify(dumpable2).dump(fd, pw, args)
        verify(dumpable3).dump(fd, pw, args)
        verify(buffer1, never()).dump(any(PrintWriter::class.java), anyInt())
        verify(buffer2, never()).dump(any(PrintWriter::class.java), anyInt())
    }

    @Test
    fun testNormalDump() {
        // GIVEN a variety of registered dumpables and buffers
        dumpManager.registerDumpable("dumpable1", dumpable1)
        dumpManager.registerDumpable("dumpable2", dumpable2)
        dumpManager.registerDumpable("dumpable3", dumpable3)
        dumpManager.registerBuffer("buffer1", buffer1)
        dumpManager.registerBuffer("buffer2", buffer2)

        // WHEN a normal dump is requested
        val args = arrayOf("--dump-priority", "NORMAL")
        dumpHandler.dump(fd, pw, args)

        // THEN all buffers are dumped (but no modules)
        verify(dumpable1, never()).dump(
                any(FileDescriptor::class.java),
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(dumpable2, never()).dump(
                any(FileDescriptor::class.java),
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(dumpable3, never()).dump(
                any(FileDescriptor::class.java),
                any(PrintWriter::class.java),
                any(Array<String>::class.java))
        verify(buffer1).dump(pw, 0)
        verify(buffer2).dump(pw, 0)
    }
}