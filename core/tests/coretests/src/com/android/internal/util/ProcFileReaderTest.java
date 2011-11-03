/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.util;

import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charsets;

/**
 * Tests for {@link ProcFileReader}.
 */
public class ProcFileReaderTest extends AndroidTestCase {

    public void testEmpty() throws Exception {
        final ProcFileReader reader = buildReader("");

        assertFalse(reader.hasMoreData());
        try {
            reader.finishLine();
            fail("somehow finished line beyond end of stream?");
        } catch (IOException e) {
            // expected
        }
        assertFalse(reader.hasMoreData());
    }

    public void testSingleString() throws Exception {
        final ProcFileReader reader = buildReader("a\nb\nc\n");

        assertEquals("a", reader.nextString());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals("b", reader.nextString());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals("c", reader.nextString());
        reader.finishLine();
        assertFalse(reader.hasMoreData());
    }

    public void testMixedNumbersSkip() throws Exception {
        final ProcFileReader reader = buildReader("1 2 3\n4 abc_def 5 6 7 8 9\n10\n");

        assertEquals(1, reader.nextInt());
        assertEquals(2, reader.nextInt());
        assertEquals(3, reader.nextInt());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals(4, reader.nextInt());
        assertEquals("abc_def", reader.nextString());
        assertEquals(5, reader.nextInt());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals(10, reader.nextInt());
        reader.finishLine();
        assertFalse(reader.hasMoreData());
    }

    public void testBufferSize() throws Exception {
        // read numbers using very small buffer size, exercising fillBuf()
        final ProcFileReader reader = buildReader("1 21 3 41 5 61 7 81 9 10\n", 3);

        assertEquals(1, reader.nextInt());
        assertEquals(21, reader.nextInt());
        assertEquals(3, reader.nextInt());
        assertEquals(41, reader.nextInt());
        assertEquals(5, reader.nextInt());
        assertEquals(61, reader.nextInt());
        assertEquals(7, reader.nextInt());
        assertEquals(81, reader.nextInt());
        assertEquals(9, reader.nextInt());
        assertEquals(10, reader.nextInt());
        reader.finishLine();
        assertFalse(reader.hasMoreData());
    }

    public void testBlankLines() throws Exception {
        final ProcFileReader reader = buildReader("1\n\n2\n\n3\n");

        assertEquals(1, reader.nextInt());
        reader.finishLine();
        assertTrue(reader.hasMoreData());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals(2, reader.nextInt());
        reader.finishLine();
        assertTrue(reader.hasMoreData());
        reader.finishLine();
        assertTrue(reader.hasMoreData());

        assertEquals(3, reader.nextInt());
        reader.finishLine();
        assertFalse(reader.hasMoreData());
    }

    public void testMinMax() throws Exception {
        final ProcFileReader reader = buildReader(
                "1 -1024 9223372036854775807 -9223372036854775808\n");

        assertEquals(1, reader.nextLong());
        assertEquals(-1024, reader.nextLong());
        assertEquals(Long.MAX_VALUE, reader.nextLong());
        assertEquals(Long.MIN_VALUE, reader.nextLong());
        reader.finishLine();
        assertFalse(reader.hasMoreData());
    }

    public void testDelimiterNeverFound() throws Exception {
        final ProcFileReader reader = buildReader("teststringwithoutdelimiters");

        try {
            reader.nextString();
            fail("somehow read a string value?");
        } catch (IOException e) {
            // expected
            assertTrue(e.getMessage().contains("end of stream"));
        }
    }

    public void testLargerThanBuffer() throws Exception {
        // try finishing line larger than buffer
        final ProcFileReader reader = buildReader("1 teststringlongerthanbuffer\n", 4);

        assertEquals(1, reader.nextLong());
        try {
            reader.finishLine();
            fail("somehow finished line?");
        } catch (IOException e) {
            // expected
            assertTrue(e.getMessage().contains("already-full buffer"));
        }
    }

    private static ProcFileReader buildReader(String string) throws IOException {
        return buildReader(string, 2048);
    }

    private static ProcFileReader buildReader(String string, int bufferSize) throws IOException {
        return new ProcFileReader(
                new ByteArrayInputStream(string.getBytes(Charsets.US_ASCII)), bufferSize);
    }
}
