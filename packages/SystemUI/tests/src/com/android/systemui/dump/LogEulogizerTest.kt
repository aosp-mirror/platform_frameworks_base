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
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.io.FakeBasicFileAttributes
import com.android.systemui.util.io.Files
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.Arrays

@SmallTest
class LogEulogizerTest : SysuiTestCase() {

    lateinit var eulogizer: LogBufferEulogizer

    @Mock
    lateinit var dumpManager: DumpManager

    @Mock
    lateinit var files: Files

    private val clock = FakeSystemClock()

    private val path = Paths.get("/foo/bar/baz.txt")
    private val fileAttrs = FakeBasicFileAttributes()
    private val fileStream = ByteArrayOutputStream()
    private val fileWriter = BufferedWriter(OutputStreamWriter(fileStream))

    private val dumpStream = ByteArrayOutputStream()
    private val dumpWriter = PrintWriter(OutputStreamWriter(dumpStream))

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        eulogizer =
                LogBufferEulogizer(dumpManager, clock, files, path, MIN_WRITE_GAP, MAX_READ_AGE)

        Mockito.`when`(files.newBufferedWriter(eq(path), any(OpenOption::class.java)))
                .thenReturn(fileWriter)

        Mockito.`when`(
                files.readAttributes(eq(path),
                eq(BasicFileAttributes::class.java),
                any(LinkOption::class.java))
        ).thenReturn(fileAttrs)

        Mockito.`when`(files.lines(eq(path))).thenReturn(Arrays.stream(FAKE_LINES))
    }

    @Test
    fun testFileIsCreated() {
        // GIVEN that the log file doesn't already exist
        Mockito.`when`(
                files.readAttributes(eq(path),
                        eq(BasicFileAttributes::class.java),
                        any(LinkOption::class.java))
        ).thenThrow(IOException("File not found"))

        // WHEN .record() is called
        val exception = RuntimeException("Something bad happened")
        assertEquals(exception, eulogizer.record(exception))

        // THEN the buffers are dumped to the file
        verify(dumpManager).dumpBuffers(any(PrintWriter::class.java), Mockito.anyInt())
        assertTrue(fileStream.toString().isNotEmpty())
    }

    @Test
    fun testExistingFileIsOverwritten() {
        // GIVEN that the log file already exists but hasn't been modified in a while
        fileAttrs.setLastModifiedTime(clock.currentTimeMillis() - MIN_WRITE_GAP - 20)

        // WHEN .record() is called
        val exception = RuntimeException("Something bad happened")
        assertEquals(exception, eulogizer.record(exception))

        // THEN the buffers are dumped to the file
        verify(dumpManager).dumpBuffers(any(PrintWriter::class.java), Mockito.anyInt())
        assertTrue(fileStream.toString().isNotEmpty())
    }

    @Test
    fun testYoungFileIsNotOverwritten() {
        // GIVEN that the log file has been modified recently
        fileAttrs.setLastModifiedTime(clock.currentTimeMillis() - MIN_WRITE_GAP + 7)

        // WHEN .record() is called
        val exception = RuntimeException("Something bad happened")
        assertEquals(exception, eulogizer.record(exception))

        // THEN the file isn't written to
        verify(dumpManager, never()).dumpBuffers(any(PrintWriter::class.java), Mockito.anyInt())
        assertTrue(fileStream.toString().isEmpty())
    }

    @Test
    fun testRecentFileIsDumped() {
        // GIVEN that the log file was written to "recently"
        fileAttrs.setLastModifiedTime(clock.currentTimeMillis() - MAX_READ_AGE + 7)

        // WHEN we're asked to eulogize the log
        eulogizer.readEulogyIfPresent(dumpWriter)
        dumpWriter.close()

        // THEN the log file is written to the output stream
        verify(files).lines(eq(path))
        assertTrue(dumpStream.toString().isNotBlank())
    }

    @Test
    fun testOldFileIsNotDumped() {
        // GIVEN that the log file was written to a long time ago
        fileAttrs.setLastModifiedTime(clock.currentTimeMillis() - MAX_READ_AGE - 7)

        // WHEN we're asked to eulogize the log
        eulogizer.readEulogyIfPresent(dumpWriter)
        dumpWriter.close()

        // THEN the log file is NOT written to the output stream
        verify(files, never()).lines(eq(path))
        assertTrue(dumpStream.toString().isEmpty())
    }
}

private const val MIN_WRITE_GAP = 10L
private const val MAX_READ_AGE = 100L

private val FAKE_LINES =
        arrayOf(
                "First line",
                "Second line",
                "Third line"
        )
