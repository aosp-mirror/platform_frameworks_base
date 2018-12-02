/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.powermodel;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests {@link PowerProfile}
 */
public class CsvParserTest {

    class LineCollector implements CsvParser.LineProcessor {
        ArrayList<ArrayList<String>> results = new ArrayList<ArrayList<String>>();

        @Override
        public void onLine(int lineNumber, ArrayList<String> fields) {
            System.out.println(lineNumber);
            for (String str: fields) {
                System.out.println("-->" + str + "<--");
            }
            results.add(fields);
        }
    }

    private void assertEquals(String[][] expected, ArrayList<ArrayList<String>> results) {
        final String[][] resultArray = new String[results.size()][];
        for (int i=0; i<results.size(); i++) {
            final ArrayList<String> list = results.get(i);
            resultArray[i] = list.toArray(new String[list.size()]);
        }
        Assert.assertArrayEquals(expected, resultArray);
    }

    private String makeString(int length) {
        final StringBuilder str = new StringBuilder();
        for (int i=0; i<length; i++) {
            str.append('a');
        }
        return str.toString();
    }

    @Test public void testEmpty() throws Exception {
        final String text = "";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                }, collector.results);
    }

    @Test public void testOnlyNewline() throws Exception {
        final String text = "\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                }, collector.results);
    }

    @Test public void testTwoLines() throws Exception {
        final String text = "one,twoo,3\nfour,5,six\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "one", "twoo", "3", },
                    { "four", "5", "six", },
                }, collector.results);
    }

    
    @Test public void testEscapedEmpty() throws Exception {
        final String text = "\"\",\"\",\"\"\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "", "", "", },
                }, collector.results);
    }

    @Test public void testEscapedText() throws Exception {
        final String text = "\"one\",\"twoo\",\"3\"\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "one", "twoo", "3", },
                }, collector.results);
    }

    @Test public void testEscapedQuotes() throws Exception {
        final String text = "\"\"\"\",\"\"\"\"\"\",\"\"\"\"\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "\"", "\"\"", "\"", },
                }, collector.results);
    }

    @Test public void testEscapedCommas() throws Exception {
        final String text = "\",\",\",\",\",\"\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { ",", ",", ",", },
                }, collector.results);
    }

    @Test public void testEscapedQuotesAndCommas() throws Exception {
        final String text = "\"\"\",\",\"\"\",\",\"\"\",\"\n";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "\",", "\",", "\",", },
                }, collector.results);
    }

    @Test public void testNoNewline() throws Exception {
        final String text = "a,b,c";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "a", "b", "c", }
                }, collector.results);
    }

    @Test public void testNoNewlineWithCommas() throws Exception {
        final String text = "a,b,,";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "a", "b", "", "" }
                }, collector.results);
    }

    @Test public void testNoNewlineWithQuote() throws Exception {
        final String text = "a,b,\",\"";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "a", "b", "," }
                }, collector.results);
    }

    @Test public void testNoCommas() throws Exception {
        final String text = "aasdfadfadfad";
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { "aasdfadfadfad", }
                }, collector.results);
    }

    @Test public void testMaxLength() throws Exception {
        final String text = makeString(CsvParser.MAX_FIELD_SIZE);
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { text, }
                }, collector.results);
    }

    @Test public void testMaxLengthTwice() throws Exception {
        String big = makeString(CsvParser.MAX_FIELD_SIZE);
        final String text = big + "," + big;
        System.out.println("Test: [" + text + "]");
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { big, big, }
                }, collector.results);
    }

    @Test public void testTooLong() throws Exception {
        final String text = makeString(CsvParser.MAX_FIELD_SIZE+1);
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        try {
            CsvParser.parse(is, collector);
            throw new RuntimeException("Expected CsvParser.parse to throw ParseException");
        } catch (ParseException ex) {
            // good
        }
    }

    @Test public void testBufferBoundary() throws Exception {
        final String big = makeString(CsvParser.MAX_FIELD_SIZE-3);
        final String text = big + ",b,c,d,e,f,g";
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { big, "b", "c", "d", "e", "f", "g", }
                }, collector.results);
    }

    @Test public void testBufferBoundaryEmpty() throws Exception {
        final String big = makeString(CsvParser.MAX_FIELD_SIZE-3);
        final String text = big + ",,,,,,";
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { big, "", "", "", "", "", "", }
                }, collector.results);
    }

    // Checks that the escaping and sawQuote behavior is correct at the buffer boundary
    @Test public void testBufferBoundaryEscapingEven() throws Exception {
        final String big = makeString(CsvParser.MAX_FIELD_SIZE-2);
        final String text = big + ",\"\"\"\"\"\"\"\"\"\"\"\"," + big;
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { big, "\"\"\"\"\"", big }
                }, collector.results);
    }

    // Checks that the escaping and sawQuote behavior is correct at the buffer boundary
    @Test public void testBufferBoundaryEscapingOdd() throws Exception {
        final String big = makeString(CsvParser.MAX_FIELD_SIZE-3);
        final String text = big + ",\"\"\"\"\"\"\"\"\"\"\"\"," + big;
        final InputStream is = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        LineCollector collector = new LineCollector();

        CsvParser.parse(is, collector);

        assertEquals(new String[][] {
                    { big, "\"\"\"\"\"", big }
                }, collector.results);
    }

}
