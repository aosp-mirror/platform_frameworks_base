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


import com.android.ide.common.rendering.api.ILayoutPullParser;
import com.android.layoutlib.bridge.impl.ParserFactory;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.BridgeXmlPullAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * {@link BridgeXmlBlockParser} reimplements most of android.xml.XmlBlock.Parser.
 * It delegates to both an instance of {@link XmlPullParser} and an instance of
 * XmlPullAttributes (for the {@link AttributeSet} part).
 */
public class BridgeXmlBlockParser implements XmlResourceParser {

    private final XmlPullParser mParser;
    private final BridgeXmlPullAttributes mAttrib;
    private final BridgeContext mContext;
    private final boolean mPlatformFile;

    private boolean mStarted = false;
    private int mEventType = START_DOCUMENT;

    private boolean mPopped = true; // default to true in case it's not pushed.

    /**
     * Builds a {@link BridgeXmlBlockParser}.
     * @param parser The XmlPullParser to get the content from.
     * @param context the Context.
     * @param platformFile Indicates whether the the file is a platform file or not.
     */
    public BridgeXmlBlockParser(XmlPullParser parser, BridgeContext context, boolean platformFile) {
        if (ParserFactory.LOG_PARSER) {
            System.out.println("CRTE " + parser.toString());
        }

        mParser = parser;
        mContext = context;
        mPlatformFile = platformFile;
        mAttrib = new BridgeXmlPullAttributes(parser, context, mPlatformFile);

        if (mContext != null) {
            mContext.pushParser(this);
            mPopped = false;
        }
    }

    public XmlPullParser getParser() {
        return mParser;
    }

    public boolean isPlatformFile() {
        return mPlatformFile;
    }

    public Object getViewCookie() {
        if (mParser instanceof ILayoutPullParser) {
            return ((ILayoutPullParser)mParser).getViewCookie();
        }

        return null;
    }

    public void ensurePopped() {
        if (mContext != null && mPopped == false) {
            mContext.popParser();
            mPopped = true;
        }
    }

    // ------- XmlResourceParser implementation

    @Override
    public void setFeature(String name, boolean state)
            throws XmlPullParserException {
        if (FEATURE_PROCESS_NAMESPACES.equals(name) && state) {
            return;
        }
        if (FEATURE_REPORT_NAMESPACE_ATTRIBUTES.equals(name) && state) {
            return;
        }
        throw new XmlPullParserException("Unsupported feature: " + name);
    }

    @Override
    public boolean getFeature(String name) {
        if (FEATURE_PROCESS_NAMESPACES.equals(name)) {
            return true;
        }
        if (FEATURE_REPORT_NAMESPACE_ATTRIBUTES.equals(name)) {
            return true;
        }
        return false;
    }

    @Override
    public void setProperty(String name, Object value) throws XmlPullParserException {
        throw new XmlPullParserException("setProperty() not supported");
    }

    @Override
    public Object getProperty(String name) {
        return null;
    }

    @Override
    public void setInput(Reader in) throws XmlPullParserException {
        mParser.setInput(in);
    }

    @Override
    public void setInput(InputStream inputStream, String inputEncoding)
            throws XmlPullParserException {
        mParser.setInput(inputStream, inputEncoding);
    }

    @Override
    public void defineEntityReplacementText(String entityName,
            String replacementText) throws XmlPullParserException {
        throw new XmlPullParserException(
                "defineEntityReplacementText() not supported");
    }

    @Override
    public String getNamespacePrefix(int pos) throws XmlPullParserException {
        throw new XmlPullParserException("getNamespacePrefix() not supported");
    }

    @Override
    public String getInputEncoding() {
        return null;
    }

    @Override
    public String getNamespace(String prefix) {
        throw new RuntimeException("getNamespace() not supported");
    }

    @Override
    public int getNamespaceCount(int depth) throws XmlPullParserException {
        throw new XmlPullParserException("getNamespaceCount() not supported");
    }

    @Override
    public String getPositionDescription() {
        return "Binary XML file line #" + getLineNumber();
    }

    @Override
    public String getNamespaceUri(int pos) throws XmlPullParserException {
        throw new XmlPullParserException("getNamespaceUri() not supported");
    }

    @Override
    public int getColumnNumber() {
        return -1;
    }

    @Override
    public int getDepth() {
        return mParser.getDepth();
    }

    @Override
    public String getText() {
        return mParser.getText();
    }

    @Override
    public int getLineNumber() {
        return mParser.getLineNumber();
    }

    @Override
    public int getEventType() {
        return mEventType;
    }

    @Override
    public boolean isWhitespace() throws XmlPullParserException {
        // Original comment: whitespace was stripped by aapt.
        return mParser.isWhitespace();
    }

    @Override
    public String getPrefix() {
        throw new RuntimeException("getPrefix not supported");
    }

    @Override
    public char[] getTextCharacters(int[] holderForStartAndLength) {
        String txt = getText();
        char[] chars = null;
        if (txt != null) {
            holderForStartAndLength[0] = 0;
            holderForStartAndLength[1] = txt.length();
            chars = new char[txt.length()];
            txt.getChars(0, txt.length(), chars, 0);
        }
        return chars;
    }

    @Override
    public String getNamespace() {
        return mParser.getNamespace();
    }

    @Override
    public String getName() {
        return mParser.getName();
    }

    @Override
    public String getAttributeNamespace(int index) {
        return mParser.getAttributeNamespace(index);
    }

    @Override
    public String getAttributeName(int index) {
        return mParser.getAttributeName(index);
    }

    @Override
    public String getAttributePrefix(int index) {
        throw new RuntimeException("getAttributePrefix not supported");
    }

    @Override
    public boolean isEmptyElementTag() {
        // XXX Need to detect this.
        return false;
    }

    @Override
    public int getAttributeCount() {
        return mParser.getAttributeCount();
    }

    @Override
    public String getAttributeValue(int index) {
        return mParser.getAttributeValue(index);
    }

    @Override
    public String getAttributeType(int index) {
        return "CDATA";
    }

    @Override
    public boolean isAttributeDefault(int index) {
        return false;
    }

    @Override
    public int nextToken() throws XmlPullParserException, IOException {
        return next();
    }

    @Override
    public String getAttributeValue(String namespace, String name) {
        return mParser.getAttributeValue(namespace, name);
    }

    @Override
    public int next() throws XmlPullParserException, IOException {
        if (!mStarted) {
            mStarted = true;

            if (ParserFactory.LOG_PARSER) {
                System.out.println("STRT " + mParser.toString());
            }

            return START_DOCUMENT;
        }

        int ev = mParser.next();

        if (ParserFactory.LOG_PARSER) {
            System.out.println("NEXT " + mParser.toString() + " " +
                    eventTypeToString(mEventType) + " -> " + eventTypeToString(ev));
        }

        if (ev == END_TAG && mParser.getDepth() == 1) {
            // done with parser remove it from the context stack.
            ensurePopped();

            if (ParserFactory.LOG_PARSER) {
                System.out.println("");
            }
        }

        mEventType = ev;
        return ev;
    }

    public static String eventTypeToString(int eventType) {
        switch (eventType) {
            case START_DOCUMENT:
                return "START_DOC";
            case END_DOCUMENT:
                return "END_DOC";
            case START_TAG:
                return "START_TAG";
            case END_TAG:
                return "END_TAG";
            case TEXT:
                return "TEXT";
            case CDSECT:
                return "CDSECT";
            case ENTITY_REF:
                return "ENTITY_REF";
            case IGNORABLE_WHITESPACE:
                return "IGNORABLE_WHITESPACE";
            case PROCESSING_INSTRUCTION:
                return "PROCESSING_INSTRUCTION";
            case COMMENT:
                return "COMMENT";
            case DOCDECL:
                return "DOCDECL";
        }

        return "????";
    }

    @Override
    public void require(int type, String namespace, String name)
            throws XmlPullParserException {
        if (type != getEventType()
                || (namespace != null && !namespace.equals(getNamespace()))
                || (name != null && !name.equals(getName())))
            throw new XmlPullParserException("expected " + TYPES[type]
                    + getPositionDescription());
    }

    @Override
    public String nextText() throws XmlPullParserException, IOException {
        if (getEventType() != START_TAG) {
            throw new XmlPullParserException(getPositionDescription()
                    + ": parser must be on START_TAG to read next text", this,
                    null);
        }
        int eventType = next();
        if (eventType == TEXT) {
            String result = getText();
            eventType = next();
            if (eventType != END_TAG) {
                throw new XmlPullParserException(
                        getPositionDescription()
                                + ": event TEXT it must be immediately followed by END_TAG",
                        this, null);
            }
            return result;
        } else if (eventType == END_TAG) {
            return "";
        } else {
            throw new XmlPullParserException(getPositionDescription()
                    + ": parser must be on START_TAG or TEXT to read text",
                    this, null);
        }
    }

    @Override
    public int nextTag() throws XmlPullParserException, IOException {
        int eventType = next();
        if (eventType == TEXT && isWhitespace()) { // skip whitespace
            eventType = next();
        }
        if (eventType != START_TAG && eventType != END_TAG) {
            throw new XmlPullParserException(getPositionDescription()
                    + ": expected start or end tag", this, null);
        }
        return eventType;
    }

    // AttributeSet implementation


    @Override
    public void close() {
        // pass
    }

    @Override
    public boolean getAttributeBooleanValue(int index, boolean defaultValue) {
        return mAttrib.getAttributeBooleanValue(index, defaultValue);
    }

    @Override
    public boolean getAttributeBooleanValue(String namespace, String attribute,
            boolean defaultValue) {
        return mAttrib.getAttributeBooleanValue(namespace, attribute, defaultValue);
    }

    @Override
    public float getAttributeFloatValue(int index, float defaultValue) {
        return mAttrib.getAttributeFloatValue(index, defaultValue);
    }

    @Override
    public float getAttributeFloatValue(String namespace, String attribute, float defaultValue) {
        return mAttrib.getAttributeFloatValue(namespace, attribute, defaultValue);
    }

    @Override
    public int getAttributeIntValue(int index, int defaultValue) {
        return mAttrib.getAttributeIntValue(index, defaultValue);
    }

    @Override
    public int getAttributeIntValue(String namespace, String attribute, int defaultValue) {
        return mAttrib.getAttributeIntValue(namespace, attribute, defaultValue);
    }

    @Override
    public int getAttributeListValue(int index, String[] options, int defaultValue) {
        return mAttrib.getAttributeListValue(index, options, defaultValue);
    }

    @Override
    public int getAttributeListValue(String namespace, String attribute,
            String[] options, int defaultValue) {
        return mAttrib.getAttributeListValue(namespace, attribute, options, defaultValue);
    }

    @Override
    public int getAttributeNameResource(int index) {
        return mAttrib.getAttributeNameResource(index);
    }

    @Override
    public int getAttributeResourceValue(int index, int defaultValue) {
        return mAttrib.getAttributeResourceValue(index, defaultValue);
    }

    @Override
    public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
        return mAttrib.getAttributeResourceValue(namespace, attribute, defaultValue);
    }

    @Override
    public int getAttributeUnsignedIntValue(int index, int defaultValue) {
        return mAttrib.getAttributeUnsignedIntValue(index, defaultValue);
    }

    @Override
    public int getAttributeUnsignedIntValue(String namespace, String attribute, int defaultValue) {
        return mAttrib.getAttributeUnsignedIntValue(namespace, attribute, defaultValue);
    }

    @Override
    public String getClassAttribute() {
        return mAttrib.getClassAttribute();
    }

    @Override
    public String getIdAttribute() {
        return mAttrib.getIdAttribute();
    }

    @Override
    public int getIdAttributeResourceValue(int defaultValue) {
        return mAttrib.getIdAttributeResourceValue(defaultValue);
    }

    @Override
    public int getStyleAttribute() {
        return mAttrib.getStyleAttribute();
    }
}
