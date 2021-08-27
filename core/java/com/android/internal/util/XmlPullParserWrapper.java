/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Objects;

/**
 * Wrapper which delegates all calls through to the given {@link XmlPullParser}.
 */
public class XmlPullParserWrapper implements XmlPullParser {
    private final XmlPullParser mWrapped;

    public XmlPullParserWrapper(@NonNull XmlPullParser wrapped) {
        mWrapped = Objects.requireNonNull(wrapped);
    }

    public void setFeature(String name, boolean state) throws XmlPullParserException {
        mWrapped.setFeature(name, state);
    }

    public boolean getFeature(String name) {
        return mWrapped.getFeature(name);
    }

    public void setProperty(String name, Object value) throws XmlPullParserException {
        mWrapped.setProperty(name, value);
    }

    public Object getProperty(String name) {
        return mWrapped.getProperty(name);
    }

    public void setInput(Reader in) throws XmlPullParserException {
        mWrapped.setInput(in);
    }

    public void setInput(InputStream inputStream, String inputEncoding)
            throws XmlPullParserException {
        mWrapped.setInput(inputStream, inputEncoding);
    }

    public String getInputEncoding() {
        return mWrapped.getInputEncoding();
    }

    public void defineEntityReplacementText(String entityName, String replacementText)
            throws XmlPullParserException {
        mWrapped.defineEntityReplacementText(entityName, replacementText);
    }

    public int getNamespaceCount(int depth) throws XmlPullParserException {
        return mWrapped.getNamespaceCount(depth);
    }

    public String getNamespacePrefix(int pos) throws XmlPullParserException {
        return mWrapped.getNamespacePrefix(pos);
    }

    public String getNamespaceUri(int pos) throws XmlPullParserException {
        return mWrapped.getNamespaceUri(pos);
    }

    public String getNamespace(String prefix) {
        return mWrapped.getNamespace(prefix);
    }

    public int getDepth() {
        return mWrapped.getDepth();
    }

    public String getPositionDescription() {
        return mWrapped.getPositionDescription();
    }

    public int getLineNumber() {
        return mWrapped.getLineNumber();
    }

    public int getColumnNumber() {
        return mWrapped.getColumnNumber();
    }

    public boolean isWhitespace() throws XmlPullParserException {
        return mWrapped.isWhitespace();
    }

    public String getText() {
        return mWrapped.getText();
    }

    public char[] getTextCharacters(int[] holderForStartAndLength) {
        return mWrapped.getTextCharacters(holderForStartAndLength);
    }

    public String getNamespace() {
        return mWrapped.getNamespace();
    }

    public String getName() {
        return mWrapped.getName();
    }

    public String getPrefix() {
        return mWrapped.getPrefix();
    }

    public boolean isEmptyElementTag() throws XmlPullParserException {
        return mWrapped.isEmptyElementTag();
    }

    public int getAttributeCount() {
        return mWrapped.getAttributeCount();
    }

    public String getAttributeNamespace(int index) {
        return mWrapped.getAttributeNamespace(index);
    }

    public String getAttributeName(int index) {
        return mWrapped.getAttributeName(index);
    }

    public String getAttributePrefix(int index) {
        return mWrapped.getAttributePrefix(index);
    }

    public String getAttributeType(int index) {
        return mWrapped.getAttributeType(index);
    }

    public boolean isAttributeDefault(int index) {
        return mWrapped.isAttributeDefault(index);
    }

    public String getAttributeValue(int index) {
        return mWrapped.getAttributeValue(index);
    }

    public String getAttributeValue(String namespace, String name) {
        return mWrapped.getAttributeValue(namespace, name);
    }

    public int getEventType() throws XmlPullParserException {
        return mWrapped.getEventType();
    }

    public int next() throws XmlPullParserException, IOException {
        return mWrapped.next();
    }

    public int nextToken() throws XmlPullParserException, IOException {
        return mWrapped.nextToken();
    }

    public void require(int type, String namespace, String name)
            throws XmlPullParserException, IOException {
        mWrapped.require(type, namespace, name);
    }

    public String nextText() throws XmlPullParserException, IOException {
        return mWrapped.nextText();
    }

    public int nextTag() throws XmlPullParserException, IOException {
        return mWrapped.nextTag();
    }
}
