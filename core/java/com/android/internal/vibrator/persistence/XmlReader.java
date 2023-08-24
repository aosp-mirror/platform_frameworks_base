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

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.vibrator.persistence.XmlConstants.NAMESPACE;

import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Helper methods for reading elements from a {@link XmlPullParser}.
 *
 * @hide
 */
public final class XmlReader {

    /**
     * Check parser is currently at {@link XmlPullParser#START_DOCUMENT} and that it has a start tag
     * with expected root tag name.
     *
     * <p>The parser will be pointing to the root start tag found after this method.
     */
    public static void readDocumentStartTag(TypedXmlPullParser parser, String expectedRootTag)
            throws XmlParserException, IOException {
        readDocumentStart(parser);

        String tagName = parser.getName();
        XmlValidator.checkParserCondition(expectedRootTag.equals(tagName),
                "Unexpected root tag found %s, expected %s", tagName, expectedRootTag);
    }

    /**
     * Check parser is currently at {@link XmlPullParser#START_DOCUMENT}.
     *
     * <p>The parser will be pointing to the first tag in the document.
     */
    public static void readDocumentStart(TypedXmlPullParser parser)
            throws XmlParserException, IOException {
        try {
            int type = parser.getEventType();
            checkArgument(
                    type == XmlPullParser.START_DOCUMENT,
                    "Unexpected type, expected %d", type);
            parser.nextTag(); // skips comments, instruction tokens and whitespace only
        } catch (XmlPullParserException e) {
            throw XmlParserException.createFromPullParserException("document start tag", e);
        }
    }

    /**
     * Check parser is currently at {@link XmlPullParser#END_TAG} and that has the expected root tag
     * name, and that the next tag is the {@link XmlPullParser#END_DOCUMENT} tag.
     *
     * <p>The parser will be pointing to the end document tag after this method.
     */
    public static void readDocumentEndTag(TypedXmlPullParser parser)
            throws XmlParserException, IOException {
        try {
            int type = parser.getEventType();
            XmlValidator.checkParserCondition(type == XmlPullParser.END_TAG,
                    "Unexpected element at document end, expected end of root tag");

            type = parser.next(); // skips comments and instruction tokens
            if (type == XmlPullParser.TEXT && parser.isWhitespace()) { // skip whitespace only
                type = parser.next();
            }

            XmlValidator.checkParserCondition(type == XmlPullParser.END_DOCUMENT,
                    "Unexpected tag found %s, expected document end", parser.getName());
        } catch (XmlPullParserException e) {
            throw XmlParserException.createFromPullParserException("document end tag", e);
        }
    }

    /**
     * Read the next tag and returns true if it's a {@link XmlPullParser#START_TAG} at depth
     * {@code outerDepth + 1} or false if it's a {@link XmlPullParser#END_TAG} at
     * {@code outerDepth}. Any other tag will fail this check.
     *
     * <p>The parser will be pointing to the next nested start tag when this method returns true,
     * or to the end tag for given depth if it returns false.
     *
     * @return true if start tag found within given depth, false otherwise
     */
    public static boolean readNextTagWithin(TypedXmlPullParser parser, int outerDepth)
            throws XmlParserException, IOException {
        int type;
        try {
            type = parser.getEventType();
            if (type == XmlPullParser.END_TAG && parser.getDepth() == outerDepth) {
                // Already pointing to the end tag at outerDepth, just return before calling next.
                return false;
            }

            type = parser.nextTag(); // skips comments, instruction tokens and whitespace only
        } catch (XmlPullParserException e) {
            throw XmlParserException.createFromPullParserException(parser.getName(), e);
        }

        if (type == XmlPullParser.START_TAG && parser.getDepth() == outerDepth + 1) {
            return true;
        }

        // Next tag is not a start tag at outerDepth+1, expect it to be the end tag for outerDepth.
        XmlValidator.checkParserCondition(
                type == XmlPullParser.END_TAG && parser.getDepth() == outerDepth,
                "Unexpected tag found %s, expected end tag at depth %d",
                parser.getName(), outerDepth);

        return false;
    }

    /**
     * Check parser has a {@link XmlPullParser#END_TAG} as the next tag, with no nested tags.
     *
     * <p>The parser will be pointing to the end tag after this method.
     */
    public static void readEndTag(TypedXmlPullParser parser)
            throws XmlParserException, IOException {
        readEndTag(parser, parser.getName(), parser.getDepth());
    }

    /**
     * Check parser has a {@link XmlPullParser#END_TAG} with same {@code tagDepth} as the next tag,
     * with no more nested start tags.
     *
     * <p>The parser will be pointing to the end tag after this method.
     */
    public static void readEndTag(TypedXmlPullParser parser, String tagName, int tagDepth)
            throws XmlParserException, IOException {
        // Read nested tag first, so we can use the parser.getName() in the error message.
        boolean hasNestedTag = readNextTagWithin(parser, tagDepth);
        XmlValidator.checkParserCondition(!hasNestedTag,
                "Unexpected nested tag %s found in tag %s", parser.getName(), tagName);
    }

    /**
     * Read attribute from current tag as a non-negative integer, returning default value if
     * attribute is missing.
     */
    public static int readAttributeIntNonNegative(
            TypedXmlPullParser parser, String attrName, int defaultValue)
            throws XmlParserException {
        if (parser.getAttributeIndex(NAMESPACE, attrName) < 0) {
            return defaultValue;
        }
        return readAttributeIntNonNegative(parser, attrName);
    }

    /** Read attribute from current tag as a non-negative integer. */
    public static int readAttributeIntNonNegative(TypedXmlPullParser parser, String attrName)
            throws XmlParserException {
        String tagName = parser.getName();
        int value = readAttributeInt(parser, attrName);

        XmlValidator.checkParserCondition(value >= 0,
                "Unexpected %s = %d in tag %s, expected %s >= 0",
                attrName, value, tagName, attrName);
        return value;
    }

    /** Read attribute from current tag as an integer within given inclusive range. */
    public static int readAttributeIntInRange(
            TypedXmlPullParser parser, String attrName, int lowerInclusive, int upperInclusive)
            throws XmlParserException {
        String tagName = parser.getName();
        int value = readAttributeInt(parser, attrName);

        XmlValidator.checkParserCondition(
                value >= lowerInclusive && value <= upperInclusive,
                "Unexpected %s = %d in tag %s, expected %s in [%d, %d]",
                attrName, value, tagName, attrName, lowerInclusive, upperInclusive);
        return value;
    }

    /**
     * Read attribute from current tag as a float within given inclusive range, returning default
     * value if attribute is missing.
     */
    public static float readAttributeFloatInRange(
            TypedXmlPullParser parser, String attrName, float lowerInclusive,
            float upperInclusive, float defaultValue) throws XmlParserException {
        if (parser.getAttributeIndex(NAMESPACE, attrName) < 0) {
            return defaultValue;
        }
        String tagName = parser.getName();
        float value = readAttributeFloat(parser, attrName);

        XmlValidator.checkParserCondition(value >= lowerInclusive && value <= upperInclusive,
                "Unexpected %s = %f in tag %s, expected %s in [%f, %f]",
                attrName, value, tagName, attrName, lowerInclusive, upperInclusive);
        return value;
    }

    private static int readAttributeInt(TypedXmlPullParser parser, String attrName)
            throws XmlParserException {
        String tagName = parser.getName();
        try {
            return parser.getAttributeInt(NAMESPACE, attrName);
        } catch (XmlPullParserException e) {
            String rawValue = parser.getAttributeValue(NAMESPACE, attrName);
            throw XmlParserException.createFromPullParserException(tagName, attrName, rawValue, e);
        }
    }

    private static float readAttributeFloat(TypedXmlPullParser parser, String attrName)
            throws XmlParserException {
        String tagName = parser.getName();
        try {
            return parser.getAttributeFloat(NAMESPACE, attrName);
        } catch (XmlPullParserException e) {
            String rawValue = parser.getAttributeValue(NAMESPACE, attrName);
            throw XmlParserException.createFromPullParserException(tagName, attrName, rawValue, e);
        }
    }
}
