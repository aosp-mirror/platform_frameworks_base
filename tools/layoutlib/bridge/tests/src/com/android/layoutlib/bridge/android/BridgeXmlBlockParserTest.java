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

package com.android.layoutlib.bridge.android;

import com.android.layoutlib.bridge.android.BridgeXmlBlockParser;

import org.kxml2.io.KXmlParser;
import org.w3c.dom.Node;
import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;

import junit.framework.TestCase;

public class BridgeXmlBlockParserTest extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testXmlBlockParser() throws Exception {
        XmlPullParser parser = new KXmlParser();
        parser = new BridgeXmlBlockParser(parser, null, false /* platformResourceFlag */);

        InputStream input = this.getClass().getClassLoader().getResourceAsStream(
            "com/android/layoutlib/testdata/layout1.xml");
        parser.setInput(input, "UTF-8"); //$NON-NLS-1$

        assertEquals(XmlPullParser.START_DOCUMENT, parser.next());

        assertEquals(XmlPullParser.START_TAG, parser.next());
        assertEquals("LinearLayout", parser.getName());

        assertEquals(XmlPullParser.TEXT, parser.next());

        assertEquals(XmlPullParser.START_TAG, parser.next());
        assertEquals("Button", parser.getName());
        assertEquals(XmlPullParser.TEXT, parser.next());
        assertEquals(XmlPullParser.END_TAG, parser.next());

        assertEquals(XmlPullParser.TEXT, parser.next());

        assertEquals(XmlPullParser.START_TAG, parser.next());
        assertEquals("View", parser.getName());
        assertEquals(XmlPullParser.END_TAG, parser.next());

        assertEquals(XmlPullParser.TEXT, parser.next());

        assertEquals(XmlPullParser.START_TAG, parser.next());
        assertEquals("TextView", parser.getName());
        assertEquals(XmlPullParser.END_TAG, parser.next());

        assertEquals(XmlPullParser.TEXT, parser.next());

        assertEquals(XmlPullParser.END_TAG, parser.next());
        assertEquals(XmlPullParser.END_DOCUMENT, parser.next());
    }

    //------------

    /**
     * Quick'n'dirty debug helper that dumps an XML structure to stdout.
     */
    @SuppressWarnings("unused")
    private void dump(Node node, String prefix) {
        Node n;

        String[] types = {
                "unknown",
                "ELEMENT_NODE",
                "ATTRIBUTE_NODE",
                "TEXT_NODE",
                "CDATA_SECTION_NODE",
                "ENTITY_REFERENCE_NODE",
                "ENTITY_NODE",
                "PROCESSING_INSTRUCTION_NODE",
                "COMMENT_NODE",
                "DOCUMENT_NODE",
                "DOCUMENT_TYPE_NODE",
                "DOCUMENT_FRAGMENT_NODE",
                "NOTATION_NODE"
        };

        String s = String.format("%s<%s> %s %s",
                prefix,
                types[node.getNodeType()],
                node.getNodeName(),
                node.getNodeValue() == null ? "" : node.getNodeValue().trim());

        System.out.println(s);

        n = node.getFirstChild();
        if (n != null) {
            dump(n, prefix + "- ");
        }

        n = node.getNextSibling();
        if (n != null) {
            dump(n, prefix);
        }

    }

}
