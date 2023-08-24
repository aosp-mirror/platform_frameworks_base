/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.vibrator.persistence;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.text.TextUtils;

import com.android.internal.util.ArrayUtils;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.util.Objects;

/**
 * Helper methods for validating elements from a {@link XmlPullParser}.
 *
 * @hide
 */
public final class XmlValidator {

    /**
     * Check parser is currently at {@link XmlPullParser#START_TAG} and that it has the expected
     * name.
     */
    public static void checkStartTag(TypedXmlPullParser parser, String expectedTag)
            throws XmlParserException {
        checkStartTag(parser);
        checkParserCondition(
                    expectedTag.equals(parser.getName()),
                    "Unexpected start tag found %s, expected %s", parser.getName(), expectedTag);
    }

    /**  Check parser is currently at {@link XmlPullParser#START_TAG}. */
    public static void checkStartTag(TypedXmlPullParser parser) throws XmlParserException {
        try {
            checkParserCondition(
                    parser.getEventType() == parser.START_TAG,
                    "Expected start tag, got " + parser.getEventType());
        } catch (XmlPullParserException e) {
            throw XmlParserException.createFromPullParserException(parser.getName(), e);
        }
    }

    /** Check current tag only has attributes from the expected list */
    public static void checkTagHasNoUnexpectedAttributes(
            TypedXmlPullParser parser, String... expectedAttributes) throws XmlParserException {
        if (expectedAttributes == null || expectedAttributes.length == 0) {
            checkParserCondition(parser.getAttributeCount() == 0,
                    "Unexpected attributes in tag %s, expected no attributes", parser.getName());
            return;
        }

        String tagName = parser.getName();
        int attributeCount = parser.getAttributeCount();

        for (int i = 0; i < attributeCount; i++) {
            String attributeName = parser.getAttributeName(i);
            checkParserCondition(ArrayUtils.contains(expectedAttributes, attributeName),
                    "Unexpected attribute %s found in tag %s", attributeName, tagName);
        }
    }

    /**
     * Check given {@link XmlSerializedVibration} represents the expected {@code vibration} object
     * when it's deserialized.
     */
    @NonNull
    public static <T> void checkSerializedVibration(
            XmlSerializedVibration<T> serializedVibration, T expectedVibration)
            throws XmlSerializerException {
        T deserializedVibration = requireNonNull(serializedVibration.deserialize());
        checkSerializerCondition(Objects.equals(expectedVibration, deserializedVibration),
                "Unexpected serialized vibration %s: found deserialization %s, expected %s",
                serializedVibration, deserializedVibration, expectedVibration);
    }

    /**
     * Check generic serializer condition
     *
     * @throws XmlSerializerException if {@code expression} is false
     */
    public static void checkSerializerCondition(boolean expression,
            String messageTemplate, Object... messageArgs) throws XmlSerializerException {
        if (!expression) {
            throw new XmlSerializerException(TextUtils.formatSimple(messageTemplate, messageArgs));
        }
    }

    /**
     * Check generic parser condition
     *
     * @throws XmlParserException if {@code expression} is false
     */
    public static void checkParserCondition(boolean expression,
            String messageTemplate, Object... messageArgs) throws XmlParserException {
        if (!expression) {
            throw new XmlParserException(TextUtils.formatSimple(messageTemplate, messageArgs));
        }
    }
}
