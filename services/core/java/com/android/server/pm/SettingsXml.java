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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Stack;

/**
 * A very specialized serialization/parsing wrapper around {@link TypedXmlSerializer} and {@link
 * TypedXmlPullParser} intended for use with PackageManager related settings files.
 * Assumptions/chosen behaviors:
 * <ul>
 *     <li>No namespace support</li>
 *     <li>Data for a parent object is stored as attributes</li>
 *     <li>All attribute read methods return a default false, -1, or null</li>
 *     <li>Default values will not be written</li>
 *     <li>Children are sub-elements</li>
 *     <li>Collections are repeated sub-elements, no attribute support for collections</li>
 * </ul>
 */
public class SettingsXml {

    private static final String TAG = "SettingsXml";

    private static final boolean DEBUG_THROW_EXCEPTIONS = false;

    private static final String FEATURE_INDENT =
            "http://xmlpull.org/v1/doc/features.html#indent-output";

    private static final int DEFAULT_NUMBER = -1;

    public static Serializer serializer(TypedXmlSerializer serializer) {
        return new Serializer(serializer);
    }

    public static ReadSection parser(TypedXmlPullParser parser)
            throws IOException, XmlPullParserException {
        return new ReadSectionImpl(parser);
    }

    public static class Serializer implements AutoCloseable {

        @NonNull
        private final TypedXmlSerializer mXmlSerializer;

        private final WriteSectionImpl mWriteSection;

        private Serializer(TypedXmlSerializer serializer) {
            mXmlSerializer = serializer;
            mWriteSection = new WriteSectionImpl(mXmlSerializer);
        }

        public WriteSection startSection(@NonNull String sectionName) throws IOException {
            return mWriteSection.startSection(sectionName);
        }

        @Override
        public void close() throws IOException {
            mWriteSection.closeCompletely();
            mXmlSerializer.flush();
        }
    }

    public interface ReadSection extends AutoCloseable {

        @NonNull
        String getName();

        @NonNull
        String getDescription();

        boolean has(String attrName);

        @Nullable
        String getString(String attrName);

        /**
         * @return value as String or {@param defaultValue} if doesn't exist
         */
        @NonNull
        String getString(String attrName, @NonNull String defaultValue);

        /**
         * @return value as boolean or false if doesn't exist
         */
        boolean getBoolean(String attrName);

        /**
         * @return value as boolean or {@param defaultValue} if doesn't exist
         */
        boolean getBoolean(String attrName, boolean defaultValue);

        /**
         * @return value as int or {@link #DEFAULT_NUMBER} if doesn't exist
         */
        int getInt(String attrName);

        /**
         * @return value as int or {@param defaultValue} if doesn't exist
         */
        int getInt(String attrName, int defaultValue);

        /**
         * @return value as long or {@link #DEFAULT_NUMBER} if doesn't exist
         */
        long getLong(String attrName);

        /**
         * @return value as long or {@param defaultValue} if doesn't exist
         */
        long getLong(String attrName, int defaultValue);

        ChildSection children();
    }

    /**
     * <pre><code>
     * ChildSection child = parentSection.children();
     * while (child.moveToNext(TAG_CHILD)) {
     *     String readValue = child.getString(...);
     *     ...
     * }
     * </code></pre>
     */
    public interface ChildSection extends ReadSection {
        boolean moveToNext();

        boolean moveToNext(@NonNull String expectedChildTagName);
    }

    public static class ReadSectionImpl implements ChildSection {

        @Nullable
        private final InputStream mInput;

        @NonNull
        private final TypedXmlPullParser mParser;

        @NonNull
        private final Stack<Integer> mDepthStack = new Stack<>();

        public ReadSectionImpl(@NonNull InputStream input)
                throws IOException, XmlPullParserException {
            mInput = input;
            mParser = Xml.newFastPullParser();
            mParser.setInput(mInput, StandardCharsets.UTF_8.name());
            moveToFirstTag();
        }

        public ReadSectionImpl(@NonNull TypedXmlPullParser parser)
                throws IOException, XmlPullParserException {
            mInput = null;
            mParser = parser;
            moveToFirstTag();
        }

        private void moveToFirstTag() throws IOException, XmlPullParserException {
            if (mParser.getEventType() == XmlPullParser.START_TAG) {
                return;
            }

            int type;
            //noinspection StatementWithEmptyBody
            while ((type = mParser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
            }
        }

        @NonNull
        @Override
        public String getName() {
            return mParser.getName();
        }

        @NonNull
        @Override
        public String getDescription() {
            return mParser.getPositionDescription();
        }

        @Override
        public boolean has(String attrName) {
            return mParser.getAttributeValue(null, attrName) != null;
        }

        @Nullable
        @Override
        public String getString(String attrName) {
            return mParser.getAttributeValue(null, attrName);
        }

        @NonNull
        @Override
        public String getString(String attrName, @NonNull String defaultValue) {
            String value = mParser.getAttributeValue(null, attrName);
            if (value == null) {
                return defaultValue;
            }
            return value;
        }

        @Override
        public boolean getBoolean(String attrName) {
            return getBoolean(attrName, false);
        }

        @Override
        public boolean getBoolean(String attrName, boolean defaultValue) {
            return mParser.getAttributeBoolean(null, attrName, defaultValue);
        }

        @Override
        public int getInt(String attrName) {
            return getInt(attrName, DEFAULT_NUMBER);
        }

        @Override
        public int getInt(String attrName, int defaultValue) {
            return mParser.getAttributeInt(null, attrName, defaultValue);
        }

        @Override
        public long getLong(String attrName) {
            return getLong(attrName, DEFAULT_NUMBER);
        }

        @Override
        public long getLong(String attrName, int defaultValue) {
            return mParser.getAttributeLong(null, attrName, defaultValue);
        }

        @Override
        public ChildSection children() {
            mDepthStack.push(mParser.getDepth());
            return this;
        }

        @Override
        public boolean moveToNext() {
            return moveToNextInternal(null);
        }

        @Override
        public boolean moveToNext(@NonNull String expectedChildTagName) {
            return moveToNextInternal(expectedChildTagName);
        }

        private boolean moveToNextInternal(@Nullable String expectedChildTagName) {
            try {
                int depth = mDepthStack.peek();
                boolean hasTag = false;
                int type;
                while (!hasTag
                        && (type = mParser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || mParser.getDepth() > depth)) {
                    if (type != XmlPullParser.START_TAG) {
                        continue;
                    }

                    if (expectedChildTagName != null
                            && !expectedChildTagName.equals(mParser.getName())) {
                        continue;
                    }

                    hasTag = true;
                }

                if (!hasTag) {
                    mDepthStack.pop();
                }

                return hasTag;
            } catch (Exception ignored) {
                return false;
            }
        }

        @Override
        public void close() throws Exception {
            if (mDepthStack.isEmpty()) {
                Slog.wtf(TAG, "Children depth stack was not empty, data may have been lost",
                        new Exception());
            }
            if (mInput != null) {
                mInput.close();
            }
        }
    }

    public interface WriteSection extends AutoCloseable {

        WriteSection startSection(@NonNull String sectionName) throws IOException;

        WriteSection attribute(String attrName, @Nullable String value) throws IOException;

        WriteSection attribute(String attrName, int value) throws IOException;

        WriteSection attribute(String attrName, long value) throws IOException;

        WriteSection attribute(String attrName, boolean value) throws IOException;

        @Override
        void close() throws IOException;

        void finish() throws IOException;
    }

    private static class WriteSectionImpl implements WriteSection {

        @NonNull
        private final TypedXmlSerializer mXmlSerializer;

        @NonNull
        private final Stack<String> mTagStack = new Stack<>();

        private WriteSectionImpl(@NonNull TypedXmlSerializer xmlSerializer) {
            mXmlSerializer = xmlSerializer;
        }

        @Override
        public WriteSection startSection(@NonNull String sectionName) throws IOException {
            // Try to start the tag first before we push it to the stack
            mXmlSerializer.startTag(null, sectionName);
            mTagStack.push(sectionName);
            return this;
        }

        @Override
        public WriteSection attribute(String attrName, String value) throws IOException {
            if (value != null) {
                mXmlSerializer.attribute(null, attrName, value);
            }
            return this;
        }

        @Override
        public WriteSection attribute(String attrName, int value) throws IOException {
            if (value != DEFAULT_NUMBER) {
                mXmlSerializer.attributeInt(null, attrName, value);
            }
            return this;
        }

        @Override
        public WriteSection attribute(String attrName, long value) throws IOException {
            if (value != DEFAULT_NUMBER) {
                mXmlSerializer.attributeLong(null, attrName, value);
            }
            return this;
        }

        @Override
        public WriteSection attribute(String attrName, boolean value) throws IOException {
            if (value) {
                mXmlSerializer.attributeBoolean(null, attrName, value);
            }
            return this;
        }

        @Override
        public void finish() throws IOException {
            close();
        }

        @Override
        public void close() throws IOException {
            mXmlSerializer.endTag(null, mTagStack.pop());
        }

        private void closeCompletely() throws IOException {
            if (DEBUG_THROW_EXCEPTIONS && mTagStack != null && !mTagStack.isEmpty()) {
                throw new IllegalStateException(
                        "tag stack is not empty when closing, contains " + mTagStack);
            } else if (mTagStack != null) {
                while (!mTagStack.isEmpty()) {
                    close();
                }
            }
        }
    }
}
