/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A wrapper around XmlPullParser that can peek forward to inspect if the file is a data-binding
 * layout and some parts need to be stripped.
 */
public class LayoutParserWrapper implements XmlPullParser {

    // Data binding constants.
    private static final String TAG_LAYOUT = "layout";
    private static final String TAG_DATA = "data";
    private static final String DEFAULT = "default=";

    private final XmlPullParser mDelegate;

    // Storage for peeked values.
    private boolean mPeeked;
    private int mEventType;
    private int mDepth;
    private int mNext;
    private List<Attribute> mAttributes;
    private String mText;
    private String mName;

    // Used to end the document before the actual parser ends.
    private int mFinalDepth = -1;
    private boolean mEndNow;

    public LayoutParserWrapper(XmlPullParser delegate) {
        mDelegate = delegate;
    }

    public LayoutParserWrapper peekTillLayoutStart() throws IOException, XmlPullParserException {
        final int STATE_LAYOUT_NOT_STARTED = 0;  // <layout> tag not encountered yet.
        final int STATE_ROOT_NOT_STARTED = 1;    // the main view root not found yet.
        final int STATE_INSIDE_DATA = 2;         // START_TAG for <data> found, but not END_TAG.

        int state = STATE_LAYOUT_NOT_STARTED;
        int dataDepth = -1;    // depth of the <data> tag. Should be two.
        while (true) {
            int peekNext = peekNext();
            switch (peekNext) {
                case START_TAG:
                    if (state == STATE_LAYOUT_NOT_STARTED) {
                        if (mName.equals(TAG_LAYOUT)) {
                            state = STATE_ROOT_NOT_STARTED;
                        } else {
                            return this; // no layout tag in the file.
                        }
                    } else if (state == STATE_ROOT_NOT_STARTED) {
                        if (mName.equals(TAG_DATA)) {
                            state = STATE_INSIDE_DATA;
                            dataDepth = mDepth;
                        } else {
                            mFinalDepth = mDepth;
                            return this;
                        }
                    }
                    break;
                case END_TAG:
                    if (state == STATE_INSIDE_DATA) {
                        if (mDepth <= dataDepth) {
                            state = STATE_ROOT_NOT_STARTED;
                        }
                    }
                    break;
                case END_DOCUMENT:
                    // No layout start found.
                    return this;
            }
            // consume the peeked tag.
            next();
        }
    }

    private int peekNext() throws IOException, XmlPullParserException {
        if (mPeeked) {
            return mNext;
        }
        mEventType = mDelegate.getEventType();
        mNext = mDelegate.next();
        if (mEventType == START_TAG) {
            int count = mDelegate.getAttributeCount();
            mAttributes = count > 0 ? new ArrayList<Attribute>(count) :
                    Collections.<Attribute>emptyList();
            for (int i = 0; i < count; i++) {
                mAttributes.add(new Attribute(mDelegate.getAttributeNamespace(i),
                        mDelegate.getAttributeName(i), mDelegate.getAttributeValue(i)));
            }
        }
        mDepth = mDelegate.getDepth();
        mText = mDelegate.getText();
        mName = mDelegate.getName();
        mPeeked = true;
        return mNext;
    }

    private void reset() {
        mAttributes = null;
        mText = null;
        mName = null;
        mPeeked = false;
    }

    @Override
    public int next() throws XmlPullParserException, IOException {
        int returnValue;
        int depth;
        if (mPeeked) {
            returnValue = mNext;
            depth = mDepth;
            reset();
        } else if (mEndNow) {
            return END_DOCUMENT;
        } else {
            returnValue = mDelegate.next();
            depth = getDepth();
        }
        if (returnValue == END_TAG && depth <= mFinalDepth) {
            mEndNow = true;
        }
        return returnValue;
    }

    @Override
    public int getEventType() throws XmlPullParserException {
        return mPeeked ? mEventType : mDelegate.getEventType();
    }

    @Override
    public int getDepth() {
        return mPeeked ? mDepth : mDelegate.getDepth();
    }

    @Override
    public String getName() {
        return mPeeked ? mName : mDelegate.getName();
    }

    @Override
    public String getText() {
        return mPeeked ? mText : mDelegate.getText();
    }

    @Override
    public String getAttributeValue(@Nullable String namespace, String name) {
        String returnValue = null;
        if (mPeeked) {
            if (mAttributes == null) {
                if (mEventType != START_TAG) {
                    throw new IndexOutOfBoundsException("getAttributeValue() called when not at START_TAG.");
                } else {
                    return null;
                }
            } else {
                for (Attribute attribute : mAttributes) {
                    //noinspection StringEquality for nullness check.
                    if (attribute.name.equals(name) && (attribute.namespace == namespace ||
                            attribute.namespace != null && attribute.namespace.equals(namespace))) {
                        returnValue = attribute.value;
                        break;
                    }
                }
            }
        } else {
            returnValue = mDelegate.getAttributeValue(namespace, name);
        }
        // Check if the value is bound via data-binding, if yes get the default value.
        if (returnValue != null && mFinalDepth >= 0 && returnValue.startsWith("@{")) {
            // TODO: Improve the detection of default keyword.
            int i = returnValue.lastIndexOf(DEFAULT);
            return i > 0 ? returnValue.substring(i + DEFAULT.length(), returnValue.length() - 1)
                    : null;
        }
        return returnValue;
    }

    private static class Attribute {
        @Nullable
        public final String namespace;
        public final String name;
        public final String value;

        public Attribute(@Nullable String namespace, String name, String value) {
            this.namespace = namespace;
            this.name = name;
            this.value = value;
        }
    }

    // Not affected by peeking.

    @Override
    public void setFeature(String s, boolean b) throws XmlPullParserException {
        mDelegate.setFeature(s, b);
    }

    @Override
    public void setProperty(String s, Object o) throws XmlPullParserException {
        mDelegate.setProperty(s, o);
    }

    @Override
    public void setInput(InputStream inputStream, String s) throws XmlPullParserException {
        mDelegate.setInput(inputStream, s);
    }

    @Override
    public void setInput(Reader reader) throws XmlPullParserException {
        mDelegate.setInput(reader);
    }

    @Override
    public String getInputEncoding() {
        return mDelegate.getInputEncoding();
    }

    @Override
    public String getNamespace(String s) {
        return mDelegate.getNamespace(s);
    }

    @Override
    public String getPositionDescription() {
        return mDelegate.getPositionDescription();
    }

    @Override
    public int getLineNumber() {
        return mDelegate.getLineNumber();
    }

    @Override
    public String getNamespace() {
        return mDelegate.getNamespace();
    }

    @Override
    public int getColumnNumber() {
        return mDelegate.getColumnNumber();
    }

    // -- We don't care much about the methods that follow.

    @Override
    public void require(int i, String s, String s1) throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public boolean getFeature(String s) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public void defineEntityReplacementText(String s, String s1) throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public Object getProperty(String s) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public int nextToken() throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public int getNamespaceCount(int i) throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getNamespacePrefix(int i) throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getNamespaceUri(int i) throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public boolean isWhitespace() throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public char[] getTextCharacters(int[] ints) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getPrefix() {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public boolean isEmptyElementTag() throws XmlPullParserException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public int getAttributeCount() {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getAttributeNamespace(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getAttributeName(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getAttributePrefix(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getAttributeType(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public boolean isAttributeDefault(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String getAttributeValue(int i) {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public String nextText() throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }

    @Override
    public int nextTag() throws XmlPullParserException, IOException {
        throw new UnsupportedOperationException("Only few parser methods are supported.");
    }
}
