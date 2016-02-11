/*
 * Copyright (C) 2013 The Android Open Source Project
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

import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for {@link IndentingPrintWriter}.
 */
public class LineBreakBufferedWriterTest extends TestCase {

    private ByteArrayOutputStream mStream;
    private RecordingWriter mWriter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mWriter = new RecordingWriter();
    }

    public void testLessThanBufferSize() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 1000);

        lw.println("Hello");
        lw.println("World");
        lw.println("Test");
        lw.flush();

        assertOutput("Hello\nWorld\nTest\n");
    }

    public void testMoreThanBufferSizeNoLineBreaks() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal = "aaaaaaaaaaaaaaa";
        lw.print(literal);
        lw.print(literal);
        lw.flush();

        // Have to manually inspect output.
        List<String> result = mWriter.getStrings();
        // Expect two strings.
        assertEquals(2, result.size());
        // Expect the strings to sum up to the original input.
        assertEquals(2 * literal.length(), result.get(0).length() + result.get(1).length());
        // Strings should only be a.
        for (String s : result) {
            for (int i = 0; i < s.length(); i++) {
                assertEquals('a', s.charAt(i));
            }
        }
    }

    public void testMoreThanBufferSizeNoLineBreaksSingleString() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal = "aaaaaaaaaaaaaaa";
        lw.print(literal + literal);
        lw.flush();

        // Have to manually inspect output.
        List<String> result = mWriter.getStrings();
        // Expect two strings.
        assertEquals(2, result.size());
        // Expect the strings to sum up to the original input.
        assertEquals(2 * literal.length(), result.get(0).length() + result.get(1).length());
        // Strings should only be a.
        for (String s : result) {
            for (int i = 0; i < s.length(); i++) {
                assertEquals('a', s.charAt(i));
            }
        }
    }

    public void testMoreThanBufferSizeLineBreakBefore() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal1 = "aaaaaaaaaa\nbbbb";
        String literal2 = "cccccccccc";
        lw.print(literal1);
        lw.print(literal2);
        lw.flush();

        assertOutput("aaaaaaaaaa", "bbbbcccccccccc");
    }

    public void testMoreThanBufferSizeLineBreakBeforeSingleString() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal1 = "aaaaaaaaaa\nbbbb";
        String literal2 = "cccccccccc";
        lw.print(literal1 + literal2);
        lw.flush();

        assertOutput("aaaaaaaaaa", "bbbbcccccccccc");
    }

    public void testMoreThanBufferSizeLineBreakNew() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal1 = "aaaaaaaaaabbbbb";
        String literal2 = "c\nd\nddddddddd";
        lw.print(literal1);
        lw.print(literal2);
        lw.flush();

        assertOutput("aaaaaaaaaabbbbbc\nd", "ddddddddd");
    }

    public void testMoreThanBufferSizeLineBreakBeforeAndNew() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal1 = "aaaaaaaaaa\nbbbbb";
        String literal2 = "c\nd\nddddddddd";
        lw.print(literal1);
        lw.print(literal2);
        lw.flush();

        assertOutput("aaaaaaaaaa\nbbbbbc\nd", "ddddddddd");
    }

    public void testMoreThanBufferSizeInt() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 15);

        int literal1 = 1234567890;
        int literal2 = 987654321;
        lw.print(literal1);
        lw.print(literal2);
        lw.flush();

        assertOutput("123456789098765", "4321");
    }

    public void testMoreThanBufferSizeChar() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 15);

        for(int i = 0; i < 10; i++) {
            lw.print('$');
        }
        for(int i = 0; i < 10; i++) {
            lw.print('%');
        }
        lw.flush();

        assertOutput("$$$$$$$$$$%%%%%", "%%%%%");
    }

    public void testMoreThanBufferSizeLineBreakNewChars() {
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 20);

        String literal1 = "aaaaaaaaaabbbbb";
        String literal2 = "c\nd\nddddddddd";
        lw.print(literal1.toCharArray());
        lw.print(literal2.toCharArray());
        lw.flush();

        assertOutput("aaaaaaaaaabbbbbc\nd", "ddddddddd");
    }

    public void testMoreThenInitialCapacitySimpleWrites() {
        // This check is different from testMoreThanBufferSizeChar. The initial capacity is lower
        // than the maximum buffer size here.
        final LineBreakBufferedWriter lw = new LineBreakBufferedWriter(mWriter, 1024, 3);

        for(int i = 0; i < 10; i++) {
            lw.print('$');
        }
        for(int i = 0; i < 10; i++) {
            lw.print('%');
        }
        lw.flush();

        assertOutput("$$$$$$$$$$%%%%%%%%%%");
    }

    private void assertOutput(String... golden) {
        List<String> goldList = createTestGolden(golden);
        assertEquals(goldList, mWriter.getStrings());
    }

    private static List<String> createTestGolden(String... args) {
        List<String> ret = new ArrayList<String>();
        for (String s : args) {
            ret.add(s);
        }
        return ret;
    }

    // A writer recording calls to write.
    private final static class RecordingWriter extends Writer {

        private List<String> strings = new ArrayList<String>();

        public RecordingWriter() {
        }

        public List<String> getStrings() {
            return strings;
        }

        @Override
        public void write(char[] cbuf, int off, int len) {
            strings.add(new String(cbuf, off, len));
        }

        @Override
        public void flush() {
            // Ignore.
        }

        @Override
        public void close() {
            // Ignore.
        }
    }
}
