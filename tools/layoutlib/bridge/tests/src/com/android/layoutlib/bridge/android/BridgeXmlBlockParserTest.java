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

import com.android.annotations.NonNull;
import com.android.ide.common.rendering.api.ActionBarCallback;
import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.resources.ResourceType;
import com.android.util.Pair;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kxml2.io.KXmlParser;
import org.w3c.dom.Node;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import static org.junit.Assert.assertEquals;

public class BridgeXmlBlockParserTest {

    @BeforeClass
    public static void setUp() {
        ParserFactory.setLayoutlibCallback(new LayoutlibTestCallback());
    }

    @Test
    public void testXmlBlockParser() throws Exception {

        XmlPullParser parser = ParserFactory.create(
                getClass().getResourceAsStream("/com/android/layoutlib/testdata/layout1.xml"),
                        "layout1.xml");

        parser = new BridgeXmlBlockParser(parser, null, false /* platformResourceFlag */);

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
     * Quick 'n' dirty debug helper that dumps an XML structure to stdout.
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

    @AfterClass
    public static void tearDown() {
        ParserFactory.setLayoutlibCallback(null);
    }

    private static class LayoutlibTestCallback extends LayoutlibCallback {

        @NonNull
        @Override
        public XmlPullParser createParser(String displayName) throws XmlPullParserException {
            return new KXmlParser();
        }

        @Override
        public boolean supports(int ideFeature) {
            throw new AssertionError();
        }

        @Override
        public Object loadView(String name, Class[] constructorSignature, Object[] constructorArgs)
                throws Exception {
            throw new AssertionError();
        }

        @Override
        public String getNamespace() {
            throw new AssertionError();
        }

        @Override
        @SuppressWarnings("deprecation")
        public Pair<ResourceType, String> resolveResourceId(int id) {
            throw new AssertionError();
        }

        @Override
        public String resolveResourceId(int[] id) {
            throw new AssertionError();
        }

        @Override
        public Integer getResourceId(ResourceType type, String name) {
            throw new AssertionError();
        }

        @Override
        @SuppressWarnings("deprecation")
        public ILayoutPullParser getParser(String layoutName) {
            throw new AssertionError();
        }

        @Override
        public ILayoutPullParser getParser(ResourceValue layoutResource) {
            throw new AssertionError();
        }

        @Override
        public Object getAdapterItemValue(ResourceReference adapterView, Object adapterCookie,
                ResourceReference itemRef, int fullPosition, int positionPerType,
                int fullParentPosition, int parentPositionPerType, ResourceReference viewRef,
                ViewAttribute viewAttribute, Object defaultValue) {
            throw new AssertionError();
        }

        @Override
        public AdapterBinding getAdapterBinding(ResourceReference adapterViewRef,
                Object adapterCookie,
                Object viewObject) {
            throw new AssertionError();
        }

        @Override
        public ActionBarCallback getActionBarCallback() {
            throw new AssertionError();
        }
    }
}
