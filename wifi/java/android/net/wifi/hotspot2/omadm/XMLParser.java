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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import android.text.TextUtils;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Class for parsing an XML string to an XML tree represented by {@link XMLNode}.
 *
 * The original XML string:
 * <root>
 *   <tag1>text1</tag1>
 *   <tag2>
 *     <tag3>text3</tag3>
 *   </tag2>
 * </root>
 *
 * The XML tree representation:
 *                  [root]
 *                     |
 *                     |
 *   [tag1, text1]-----|-----[tag2]
 *                             |
 *                             |
 *                       [tag3, text3]
 *
 * @hide
 */
public class XMLParser extends DefaultHandler {
    private XMLNode mRoot = null;
    private XMLNode mCurrent = null;

    public XMLNode parse(String text) throws IOException, SAXException {
        if (TextUtils.isEmpty(text)) {
            throw new IOException("XML string not provided");
        }

        // Reset pointers.
        mRoot = null;
        mCurrent = null;

        try {
            SAXParser parser = SAXParserFactory.newInstance().newSAXParser();
            parser.parse(new InputSource(new StringReader(text)), this);
            return mRoot;
        } catch (ParserConfigurationException pce) {
            throw new SAXException(pce);
        }
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {
        XMLNode parent = mCurrent;

        mCurrent = new XMLNode(parent, qName);

        if (mRoot == null) {
            mRoot = mCurrent;
        } else if (parent == null) {
            throw new SAXException("More than one root nodes");
        } else {
            parent.addChild(mCurrent);
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        if (!qName.equals(mCurrent.getTag())) {
            throw new SAXException("End tag '" + qName + "' doesn't match current node: " +
                    mCurrent);
        }

        mCurrent.close();
        mCurrent = mCurrent.getParent();
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        mCurrent.addText(new String(ch, start, length));
    }
}
