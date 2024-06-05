/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// this test causes a IllegalAccessError: superclass not accessible
@RunWith(AndroidJUnit4.class)
public class LoggingPrintStreamTest {

    TestPrintStream out = new TestPrintStream();

    @Test
    public void testPrintException() {
        @SuppressWarnings("ThrowableInstanceNeverThrown")
        Throwable t = new Throwable("Ignore me.");

        StringWriter sout = new StringWriter();
        t.printStackTrace(new PrintWriter(sout));

        t.printStackTrace(out);
        // t.printStackTrace();

        String[] lines = sout.toString().split("\\n");
        assertEquals(Arrays.asList(lines), out.lines);
    }

    @Test
    public void testPrintObject() {
        Object o = new Object();
        out.print(4);
        out.print(o);
        out.print(2);
        out.flush();
        assertEquals(Arrays.asList("4" + o + "2"), out.lines);
    }

    @Test
    public void testPrintlnObject() {
        Object o = new Object();
        out.print(4);
        out.println(o);
        out.print(2);
        out.flush();
        assertEquals(Arrays.asList("4" + o, "2"), out.lines);
    }

    @Test
    public void testPrintf() {
        out.printf("Name: %s\nEmployer: %s", "Bob", "Google");
        assertEquals(Arrays.asList("Name: Bob"), out.lines);
        out.flush();
        assertEquals(Arrays.asList("Name: Bob", "Employer: Google"), out.lines);
    }

    @Test
    public void testPrintInt() {
        out.print(4);
        out.print(2);
        assertTrue(out.lines.isEmpty());
        out.flush();
        assertEquals(Collections.singletonList("42"), out.lines);
    }

    @Test
    public void testPrintlnInt() {
        out.println(4);
        out.println(2);
        assertEquals(Arrays.asList("4", "2"), out.lines);
    }

    @Test
    public void testPrintCharArray() {
        out.print("Foo\nBar\nTee".toCharArray());
        assertEquals(Arrays.asList("Foo", "Bar"), out.lines);
        out.flush();
        assertEquals(Arrays.asList("Foo", "Bar", "Tee"), out.lines);
    }

    @Test
    public void testPrintString() {
        out.print("Foo\nBar\nTee");
        assertEquals(Arrays.asList("Foo", "Bar"), out.lines);
        out.flush();
        assertEquals(Arrays.asList("Foo", "Bar", "Tee"), out.lines);
    }

    @Test
    public void testPrintlnCharArray() {
        out.println("Foo\nBar\nTee".toCharArray());
        assertEquals(Arrays.asList("Foo", "Bar", "Tee"), out.lines);
    }

    @Test
    public void testPrintlnString() {
        out.println("Foo\nBar\nTee");
        assertEquals(Arrays.asList("Foo", "Bar", "Tee"), out.lines);
    }

    @Test
    public void testPrintlnStringWithBufferedData() {
        out.print(5);
        out.println("Foo\nBar\nTee");
        assertEquals(Arrays.asList("5Foo", "Bar", "Tee"), out.lines);
    }

    @Test
    public void testAppend() {
        out.append("Foo\n")
            .append('4')
            .append('\n')
            .append("Bar", 1, 2)
            .append('\n');
        assertEquals(Arrays.asList("Foo", "4", "a"), out.lines);
    }

    @Test
    public void testMultiByteCharactersSpanningBuffers() throws Exception {
        // assume 3*1000 bytes won't fit in LoggingPrintStream's internal buffer
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            builder.append("\u20AC"); // a Euro character; 3 bytes in UTF-8
        }
        String expected = builder.toString();

        out.write(expected.getBytes("UTF-8"));
        out.flush();
        assertEquals(Arrays.asList(expected), out.lines);
    }

    @Test
    public void testWriteOneByteAtATimeMultibyteCharacters() throws Exception {
        String expected = " \u20AC  \u20AC   \u20AC    \u20AC     ";
        for (byte b : expected.getBytes()) {
            out.write(b);
        }
        out.flush();
        assertEquals(Arrays.asList(expected), out.lines);
    }

    @Test
    public void testWriteByteArrayAtATimeMultibyteCharacters() throws Exception {
        String expected = " \u20AC  \u20AC   \u20AC    \u20AC     ";
        out.write(expected.getBytes());
        out.flush();
        assertEquals(Arrays.asList(expected), out.lines);
    }

    @Test
    public void testWriteWithOffsetsMultibyteCharacters() throws Exception {
        String expected = " \u20AC  \u20AC   \u20AC    \u20AC     ";
        byte[] bytes = expected.getBytes();
        int i = 0;
        while (i < bytes.length - 5) {
            out.write(bytes, i, 5);
            i += 5;
        }
        out.write(bytes, i, bytes.length - i);
        out.flush();
        assertEquals(Arrays.asList(expected), out.lines);
    }

    @Test
    public void testWriteFlushesOnNewlines() throws Exception {
        String a = " \u20AC  \u20AC ";
        String b = "  \u20AC    \u20AC  ";
        String c = "   ";
        String toWrite = a + "\n" + b + "\n" + c;
        out.write(toWrite.getBytes());
        out.flush();
        assertEquals(Arrays.asList(a, b, c), out.lines);
    }

    static class TestPrintStream extends LoggingPrintStream {

        final List<String> lines = new ArrayList<String>();

        protected void log(String line) {
            lines.add(line);
        }
    }
}
