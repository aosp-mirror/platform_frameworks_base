/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.net.wifi.hotspot2.omadm;

import static org.junit.Assert.assertTrue;

import android.net.wifi.hotspot2.omadm.XMLNode;
import android.net.wifi.hotspot2.omadm.XMLParser;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.IOException;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.omadm.XMLParser}.
 */
@SmallTest
public class XMLParserTest {
    XMLParser mParser;

    private static XMLNode createNode(XMLNode parent, String tag, String text) {
        XMLNode node = new XMLNode(parent, tag);
        node.addText(text);
        if (parent != null)
            parent.addChild(node);
        node.close();
        return node;
    }

    /**
     * Setup before tests.
     */
    @Before
    public void setUp() throws Exception {
        mParser = new XMLParser();
    }

    @Test(expected = IOException.class)
    public void parseNullXML() throws Exception {
        mParser.parse(null);
    }

    @Test(expected = IOException.class)
    public void parseEmptyXML() throws Exception {
        mParser.parse(new String());
    }

    @Test(expected = SAXException.class)
    public void parseMalformedXML() throws Exception {
        String malformedXmlTree = "<root><child1>test1</child2></root>";
        mParser.parse(malformedXmlTree);
    }

    @Test
    public void parseValidXMLTree() throws Exception {
        String xmlTree = "<root><child1>test1</child1><child2>test2</child2></root>";

        // Construct the expected XML tree.
        XMLNode expectedRoot = createNode(null, "root", "");
        createNode(expectedRoot, "child1", "test1");
        createNode(expectedRoot, "child2", "test2");

        XMLNode actualRoot = mParser.parse(xmlTree);
        assertTrue(actualRoot.equals(expectedRoot));
    }
}
