/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.unit_tests;

import com.google.android.util.SimplePullParser;
import junit.framework.TestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class SimplePullParserTest extends TestCase {
    @SmallTest
    public void testTwoLevels() throws Exception {
        String xml = ""
                + "<top a='1' b='hello'>\n"
                + "  <next c='2' d='there'/>\n"
                + "  <next c='3' d='bye'/>\n"
                + "</top>";
        SimplePullParser parser = new SimplePullParser(xml);
        int depth0 = parser.getDepth();
        assertEquals(0, depth0);
        assertEquals("top", parser.nextTag(depth0));
        assertEquals(1, parser.getIntAttribute(null, "a"));
        assertEquals("hello", parser.getStringAttribute(null, "b"));

        int depth1 = parser.getDepth();
        assertEquals(1, depth1);
        assertEquals("next", parser.nextTag(depth1));
        assertEquals(2, parser.getIntAttribute(null, "c"));
        assertEquals("there", parser.getStringAttribute(null, "d"));
        assertEquals("next", parser.nextTag(depth1));
        assertEquals(3, parser.getIntAttribute(null, "c"));
        assertEquals("bye", parser.getStringAttribute(null, "d"));
        assertNull(parser.nextTag(depth1));

        assertNull(parser.nextTag(depth0));
    }

    @SmallTest
    public void testAttributes() throws Exception {
        String xml = "<top a='1' b='hello'/>";
        SimplePullParser parser = new SimplePullParser(xml);
        int depth = parser.getDepth();
        parser.nextTag(depth);

        assertEquals(2, parser.numAttributes());
        assertEquals("a", parser.getAttributeName(0));
        assertEquals("b", parser.getAttributeName(1));

        assertEquals(1, parser.getIntAttribute(null, "a"));
        assertEquals(5, parser.getIntAttribute(null, "c", 5));
        assertEquals("hello", parser.getStringAttribute(null, "b"));
        assertEquals("not", parser.getStringAttribute(null, "d", "not"));
    }

    @SmallTest
    public void testRecovery() throws Exception {
        String xml = ""
                + "<top a='1' b='hello'>\n"
                + "  <middle c='2' d='there'>\n"
                + "    <inner/>\n"
                + "    <inner2/>\n"
                + "    <inner3/>\n"
                + "  </middle>\n"
                + "  <middle2/>\n"
                + "</top>";
        SimplePullParser parser = new SimplePullParser(xml);
        assertEquals(0, parser.getDepth());
        assertEquals("top", parser.nextTag(0));
        assertEquals(1, parser.getDepth());
        assertEquals("middle", parser.nextTag(1));
        assertEquals(2, parser.getDepth());
        assertEquals("inner", parser.nextTag(2));
        // Now skip some elements.
        assertEquals("middle2", parser.nextTag(1));
    }

    @SmallTest
    public void testCdata() throws Exception {
        StringBuilder cdataBuilder;
        String xml = ""
                + "<top>"
                + "<![CDATA[data0]]>"
                + "<next0/>"
                + "<![CDATA[data1]]>"
                + "<next1/>"
                + "<![CDATA[data2]]>"
                + "<next2/>"
                + "<![CDATA[data3]]>"
                + "<next3/>"
                + "<![CDATA[data4]]>"
                + "<next4/>"
                + "<![CDATA[data5]]>"
                + "</top>";
        SimplePullParser parser = new SimplePullParser(xml);
        assertEquals("top", parser.nextTag(0));

        // We can ignore cdata by not passing a cdata builder.
        assertEquals("next0", parser.nextTag(1));

        // We can get the most recent cdata by passing an empty cdata builder.
        cdataBuilder = new StringBuilder();
        assertSame(SimplePullParser.TEXT_TAG, parser.nextTagOrText(1, cdataBuilder));
        assertEquals("data1", cdataBuilder.toString());
        assertEquals("next1", parser.nextTag(1));

        // We can join multiple cdatas by reusing a builder.
        cdataBuilder = new StringBuilder();
        assertSame(SimplePullParser.TEXT_TAG, parser.nextTagOrText(1, cdataBuilder));
        assertEquals("next2", parser.nextTag(1));
        assertSame(SimplePullParser.TEXT_TAG, parser.nextTagOrText(1, cdataBuilder));
        assertEquals("data2data3", cdataBuilder.toString());
        assertEquals("next3", parser.nextTag(1));

        // We can read all of the remaining cdata while ignoring any elements.
        cdataBuilder = new StringBuilder();
        parser.readRemainingText(1, cdataBuilder);
        assertEquals("data4data5", cdataBuilder.toString());
    }
}
