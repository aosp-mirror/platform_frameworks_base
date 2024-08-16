/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.vibrator;

import android.content.res.XmlResourceParser;
import android.util.Xml;

import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;

/**
 * Wrapper to use TypedXmlPullParser as XmlResourceParser for Resources.getXml(). This is borrowed
 * from {@code ZenModeHelperTest}.
 */
public final class FakeXmlResourceParser implements XmlResourceParser {
    private final TypedXmlPullParser mParser;

    public FakeXmlResourceParser(TypedXmlPullParser parser) {
        this.mParser = parser;
    }

    /** Create a {@link FakeXmlResourceParser} given a xml {@link String}. */
    public static XmlResourceParser fromXml(String xml) throws XmlPullParserException {
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())), null);
        return new FakeXmlResourceParser(parser);
    }

    @Override
    public int getEventType() throws XmlPullParserException {
        return mParser.getEventType();
    }

    @Override
    public void setFeature(String name, boolean state) throws XmlPullParserException {
        mParser.setFeature(name, state);
    }

    @Override
    public boolean getFeature(String name) {
        return false;
    }

    @Override
    public void setProperty(String name, Object value) throws XmlPullParserException {
        mParser.setProperty(name, value);
    }

    @Override
    public Object getProperty(String name) {
        return mParser.getProperty(name);
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
    public String getInputEncoding() {
        return mParser.getInputEncoding();
    }

    @Override
    public void defineEntityReplacementText(String entityName, String replacementText)
            throws XmlPullParserException {
        mParser.defineEntityReplacementText(entityName, replacementText);
    }

    @Override
    public int getNamespaceCount(int depth) throws XmlPullParserException {
        return mParser.getNamespaceCount(depth);
    }

    @Override
    public String getNamespacePrefix(int pos) throws XmlPullParserException {
        return mParser.getNamespacePrefix(pos);
    }

    @Override
    public String getNamespaceUri(int pos) throws XmlPullParserException {
        return mParser.getNamespaceUri(pos);
    }

    @Override
    public String getNamespace(String prefix) {
        return mParser.getNamespace(prefix);
    }

    @Override
    public int getDepth() {
        return mParser.getDepth();
    }

    @Override
    public String getPositionDescription() {
        return mParser.getPositionDescription();
    }

    @Override
    public int getLineNumber() {
        return mParser.getLineNumber();
    }

    @Override
    public int getColumnNumber() {
        return mParser.getColumnNumber();
    }

    @Override
    public boolean isWhitespace() throws XmlPullParserException {
        return mParser.isWhitespace();
    }

    @Override
    public String getText() {
        return mParser.getText();
    }

    @Override
    public char[] getTextCharacters(int[] holderForStartAndLength) {
        return mParser.getTextCharacters(holderForStartAndLength);
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
    public String getPrefix() {
        return mParser.getPrefix();
    }

    @Override
    public boolean isEmptyElementTag() throws XmlPullParserException {
        return false;
    }

    @Override
    public int getAttributeCount() {
        return mParser.getAttributeCount();
    }

    @Override
    public int next() throws IOException, XmlPullParserException {
        return mParser.next();
    }

    @Override
    public int nextToken() throws XmlPullParserException, IOException {
        return mParser.next();
    }

    @Override
    public void require(int type, String namespace, String name)
            throws XmlPullParserException, IOException {
        mParser.require(type, namespace, name);
    }

    @Override
    public String nextText() throws XmlPullParserException, IOException {
        return mParser.nextText();
    }

    @Override
    public String getAttributeNamespace(int index) {
        return "";
    }

    @Override
    public String getAttributeName(int index) {
        return mParser.getAttributeName(index);
    }

    @Override
    public String getAttributePrefix(int index) {
        return mParser.getAttributePrefix(index);
    }

    @Override
    public String getAttributeType(int index) {
        return mParser.getAttributeType(index);
    }

    @Override
    public boolean isAttributeDefault(int index) {
        return mParser.isAttributeDefault(index);
    }

    @Override
    public String getAttributeValue(int index) {
        return mParser.getAttributeValue(index);
    }

    @Override
    public String getAttributeValue(String namespace, String name) {
        return mParser.getAttributeValue(namespace, name);
    }

    @Override
    public int getAttributeNameResource(int index) {
        return 0;
    }

    @Override
    public int getAttributeListValue(String namespace, String attribute, String[] options,
            int defaultValue) {
        return 0;
    }

    @Override
    public boolean getAttributeBooleanValue(String namespace, String attribute,
            boolean defaultValue) {
        return false;
    }

    @Override
    public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
        return 0;
    }

    @Override
    public int getAttributeIntValue(String namespace, String attribute, int defaultValue) {
        return 0;
    }

    @Override
    public int getAttributeUnsignedIntValue(String namespace, String attribute,
            int defaultValue) {
        return 0;
    }

    @Override
    public float getAttributeFloatValue(String namespace, String attribute,
            float defaultValue) {
        return 0;
    }

    @Override
    public int getAttributeListValue(int index, String[] options, int defaultValue) {
        return 0;
    }

    @Override
    public boolean getAttributeBooleanValue(int index, boolean defaultValue) {
        return false;
    }

    @Override
    public int getAttributeResourceValue(int index, int defaultValue) {
        return 0;
    }

    @Override
    public int getAttributeIntValue(int index, int defaultValue) {
        return 0;
    }

    @Override
    public int getAttributeUnsignedIntValue(int index, int defaultValue) {
        return 0;
    }

    @Override
    public float getAttributeFloatValue(int index, float defaultValue) {
        return 0;
    }

    @Override
    public String getIdAttribute() {
        return null;
    }

    @Override
    public String getClassAttribute() {
        return null;
    }

    @Override
    public int getIdAttributeResourceValue(int defaultValue) {
        return 0;
    }

    @Override
    public int getStyleAttribute() {
        return 0;
    }

    @Override
    public void close() {
    }

    @Override
    public int nextTag() throws IOException, XmlPullParserException {
        return mParser.nextTag();
    }
}
